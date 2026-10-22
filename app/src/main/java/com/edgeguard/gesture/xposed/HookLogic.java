package com.edgeguard.gesture.xposed;

import android.os.SystemClock;
import android.view.MotionEvent;

import com.edgeguard.gesture.common.Logx;
import com.edgeguard.gesture.common.RuleSet;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 手势闸门（真正做"屏蔽/放行"决策的地方）。
 *
 * 核心策略 —— "整条手势不喂给系统"：
 *   在 ACTION_DOWN 时判断该侧是否被屏蔽；一旦屏蔽，就把这条手势流的
 *   DOWN / MOVE / POINTER_* / UP 全部吞掉（不调用原方法、并让 Hook 方法直接返回），
 *   原处理器根本收不到 DOWN，因此它的状态机不会启动，返回手势/底部手势都不会触发。
 *
 * 为什么不用"设置 mAllowGesture=false"：
 *   AOSP 的 onMotionEvent 内部会在 DOWN 时自己重算 mAllowGesture，
 *   外部改字段既可能被覆盖，也可能与 LineageOS 的额外补丁冲突。
 *   完全不喂事件是语义最干净、跨版本最稳的做法，而且天然不影响未屏蔽的一侧
 *   （未屏蔽时我们完全不动参数，原逻辑分毫不变）。
 *
 * 事件流用 (downTime, displayId) 做 key，多指/多屏互不干扰；5 秒兜底清理，
 * 防止丢 UP 事件后"永久屏蔽"。
 */
final class HookLogic {

    private static final Object LOCK = new Object();
    /** 无锁读取：输入回调里不再为了查表加锁。 */
    private static final Map<Long, Long> BLOCKED_STREAMS = new ConcurrentHashMap<>();
    private static final long STREAM_TTL_MS = 5000L;

    private HookLogic() {
    }

    /** 旧架构入口：EdgeBackGestureHandler 的 onMotionEvent 家族（事件可能来自屏幕任意位置）。 */
    static boolean onEdgeTouch(Object handler, MotionEvent ev) {
        return gate(handler, ev, true, false);
    }

    /**
     * 新架构入口（A16/A17）：DisplayBackGestureHandlerImpl 通过 InputMonitor 的 gesture monitor
     * 直接收事件，能进到这里的事件本身就已落在返回手势区域。
     * 因此不再用"边宽猜测"，直接按 x 落在屏幕左半/右半判定，避免边宽读不到导致漏屏蔽。
     */
    static boolean onEdgeTouchFromGestureMonitor(Object handler, MotionEvent ev) {
        return gate(handler, ev, true, true);
    }

    /** 底部手势入口（若该版本 SystemUI 里存在 HomeHandle 等）。 */
    static boolean onBottomTouch(Object handler, MotionEvent ev) {
        return gate(handler, ev, false, false);
    }

    /** finishInputEvent 失败时撤销本条流的屏蔽标记，避免把后续事件也一起吞掉。 */
    static void abortStream(MotionEvent ev) {
        if (ev == null) {
            return;
        }
        synchronized (LOCK) {
            BLOCKED_STREAMS.remove(streamKey(ev));
        }
    }

    private static boolean gate(Object handler, MotionEvent ev, boolean edge, boolean trustedEdge) {
        if (ev == null) {
            return false;
        }
        // 最省电的快速路径①：一条规则都没有时，连事件内容都不看，直接放行。
        RuleSet snapshot = HookRuntime.rules;
        if (snapshot == null || snapshot.size() == 0) {
            return false;
        }
        // 快速路径②：前台不是"需要屏蔽"的应用时，直接放行。
        // 这一步只读两个 volatile 字段（缓存的前台包名 + 规则表），没有 Binder、没有分配。
        if (ForegroundTracker.isTracking()) {
            ForegroundTracker.Fg fg = ForegroundTracker.cachedOnly();
            if (fg == null || fg.packageName == null) {
                return false;
            }
            RuleSet.Decision d = snapshot.decide(fg.packageName, fg.activityName);
            if (d == null || (!d.blockLeft && !d.blockRight && !d.blockBottom)) {
                return false;
            }
        }
        final int action = ev.getActionMasked();
        final long key = streamKey(ev);
        final long now = SystemClock.uptimeMillis();
        synchronized (LOCK) {
            expire(now);
            if (action == MotionEvent.ACTION_DOWN) {
                // 先判"这条手势是否落在需要判断的区域"：
                // 不落在左/右边缘的手势（例如底部导航条中段）完全不做前台查询，
                // 把开销和误判风险都降到最低。
                boolean candidate = false;
                String side = "";
                int detectedSide = EdgeMetrics.SIDE_NONE;
                if (edge && trustedEdge) {
                    // gesture monitor 只送边缘事件：按屏幕左右半区判定
                    int width = EdgeMetrics.displayWidth(handler);
                    detectedSide = ev.getX() < width / 2f
                            ? EdgeMetrics.SIDE_LEFT
                            : EdgeMetrics.SIDE_RIGHT;
                    candidate = true;
                    side = detectedSide == EdgeMetrics.SIDE_LEFT ? "LEFT" : "RIGHT";
                } else if (edge) {
                    detectedSide = EdgeMetrics.sideOf(handler, ev);
                    if (detectedSide == EdgeMetrics.SIDE_LEFT) {
                        candidate = true;
                        side = "LEFT";
                    } else if (detectedSide == EdgeMetrics.SIDE_RIGHT) {
                        candidate = true;
                        side = "RIGHT";
                    }
                } else {
                    candidate = true;
                    side = "BOTTOM";
                }
                boolean block = false;
                if (candidate) {
                    RuleSet.Decision decision = decisionForForeground();
                    if (detectedSide == EdgeMetrics.SIDE_LEFT) {
                        block = decision.blockLeft;
                    } else if (detectedSide == EdgeMetrics.SIDE_RIGHT) {
                        block = decision.blockRight;
                    } else if (!edge) {
                        block = decision.blockBottom;
                    }
                }
                if (candidate && HookRuntime.DEBUG) {
                    diagDown(side, block, ev);
                }
                if (block) {
                    BLOCKED_STREAMS.put(key, now);
                    // 只在真正屏蔽时记一条（同一侧 2 秒内最多一条），避免刷屏与额外 IO
                long last = lastBlockLogAt;
                if (now - last > 2000L) {
                    lastBlockLogAt = now;
                    Xlog.step("BLOCK " + side + " gesture");
                }
                }
            }
            Long started = BLOCKED_STREAMS.get(key);
            if (started == null) {
                return false;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                BLOCKED_STREAMS.remove(key);
            }
            return true;
        }
    }

    private static long lastDiagAt;
    private static long lastBlockLogAt;

    /** 把"这次边缘触摸到底判成什么"写进 modules.log（限流 400ms，避免刷屏）。 */
    private static void diagDown(String side, boolean block, MotionEvent ev) {
        long now = SystemClock.uptimeMillis();
        if (now - lastDiagAt < 400L) {
            return;
        }
        lastDiagAt = now;
        try {
            ForegroundTracker.Fg fg = ForegroundTracker.get();
            Xlog.debug("edge DOWN side=" + side + " block=" + block
                    + " x=" + (int) ev.getX()
                    + " fg=" + (fg == null ? "null" : fg.packageName)
                    + " rules=" + (HookRuntime.rules == null ? 0 : HookRuntime.rules.size()));
        } catch (Throwable ignored) {
        }
    }

    /**
     * 返回真正触发时的拦截（A16/A17 最可靠的一道）。
     *
     * DisplayBackGestureHandlerImpl 的输入链只是一半：手势达标后系统会回调
     * EdgeBackGestureHandler 的 BackCallback#onBackInvoked()，那里才是"执行返回"的地方。
     * 我们在这里按 mIsOnLeftEdge 判断左右，再查一次规则，命中就不调用原方法。
     */
    static boolean shouldBlockBackInvocation(Object callback) {
        try {
            Object handler = Reflect.fieldValue(callback, "this$0", "this$0$");
            if (handler == null) {
                Xlog.step("backInvoked: no outer handler, allow");
                return false;
            }
            boolean onLeftEdge = Reflect.booleanField(handler,
                    Reflect.field(handler.getClass(), "mIsOnLeftEdge", "mOnLeftEdge"), false);
            RuleSet.Decision decision = decisionForForeground();
            boolean block = onLeftEdge ? decision.blockLeft : decision.blockRight;
            Xlog.debug("backInvoked: left=" + onLeftEdge + " block=" + block + " fg=" + describeForeground());
            return block;
        } catch (Throwable t) {
            Xlog.step("backInvoked check failed: " + t);
            return false;
        }
    }

    /**
     * 触摸回调里只做：读一次 volatile 规则引用 + 一次 map 查询。
     * 前台检测本身有 400ms 缓存，不会每次触摸都走 Binder。
     */
    private static RuleSet.Decision decisionForForeground() {
        try {
            RuleSet rules = HookRuntime.rules;
            if (rules == null || rules.size() == 0) {
                return RuleSet.Decision.NONE;
            }
            // 有推送式前台跟踪时，只用缓存（触摸线程绝不阻塞在 Binder 上）
            ForegroundTracker.Fg fg = ForegroundTracker.isTracking()
                    ? ForegroundTracker.cachedOnly()
                    : ForegroundTracker.get();
            if (fg == null || fg.packageName == null || fg.packageName.length() == 0) {
                return RuleSet.Decision.NONE;
            }
            return rules.decide(fg.packageName, fg.activityName);
        } catch (Throwable t) {
            // 任何异常 -> 不屏蔽（恢复系统默认行为）
            Logx.w("decision failed, fallback to no-block", t);
            return RuleSet.Decision.NONE;
        }
    }

    private static String describeForeground() {
        try {
            ForegroundTracker.Fg fg = ForegroundTracker.get();
            return fg == null ? "unknown(不屏蔽)" : fg.describe();
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private static long streamKey(MotionEvent ev) {
        // 只依赖公开 API：downTime 在一次手势（含多指）内唯一。
        // （MotionEvent.getDisplayId() 是 @hide，android.jar 里没有，不能直接调用。）
        return ev.getDownTime();
    }

    private static void expire(long now) {
        if (BLOCKED_STREAMS.size() > 16) {
            BLOCKED_STREAMS.clear();
            return;
        }
        Iterator<Map.Entry<Long, Long>> it = BLOCKED_STREAMS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Long> e = it.next();
            if (now - e.getValue() > STREAM_TTL_MS) {
                it.remove();
            }
        }
    }
}
