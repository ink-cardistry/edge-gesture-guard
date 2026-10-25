package com.edgeguard.gesture.xposed;

import android.os.SystemClock;
import android.view.MotionEvent;

import com.edgeguard.gesture.common.Logx;
import com.edgeguard.gesture.common.ModuleConfig;
import com.edgeguard.gesture.common.ModuleContract;
import com.edgeguard.gesture.common.RuleSet;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 手势闸门（真正做"屏蔽/放行"决策的地方）。
 *
 * 核心策略 —— "整条手势不喂给系统"：
 *   在 ACTION_DOWN 时判断这条手势是否该屏蔽；一旦屏蔽，就把这条手势流的
 *   DOWN / MOVE / POINTER_* / UP 全部吞掉（不调用原方法、并让 Hook 方法直接返回），
 *   原处理器根本收不到 DOWN，因此它的状态机不会启动，手势不会触发。
 *
 * 四条通道共用同一套闸门，但判定条件不同：
 *
 *   KIND_EDGE（A16/17 返回手势 gesture monitor）
 *       - 事件本身来自 gesture monitor；
 *       - 先看"起点是否落在底部区且开了 blockBottom"（策略 A）；
 *       - 否则按屏幕左右半区判左右。
 *
 *   KIND_BOTTOM_HANDLE（旧架构 HomeHandle 之类的 SystemUI 底部手柄）
 *       - 一律按 blockBottom 判。
 *
 *   KIND_LAUNCHER（策略 B：Launcher 进程 TouchInteractionService）
 *       - 事件在底部手势通道上，仍要求起点落在底部区（双重校验，防止坐标口径不一致误吞）；
 *       - 只在用户显式打开 launcherBottom 后才生效。
 *
 *   KIND_SHADE（顶部下拉）
 *       - 要求起点落在顶部区 + blockTopShade + 通知栏当前不是"已展开"状态。
 *
 * 事件流 key = (downTime, kind)：多指/多通道互不干扰；5 秒兜底清理，
 * 防止丢 UP 事件后"永久屏蔽"。
 */
final class HookLogic {

    static final int KIND_EDGE = 1;
    static final int KIND_BOTTOM_HANDLE = 2;
    static final int KIND_SHADE = 3;
    static final int KIND_LAUNCHER = 4;

    private static final Object LOCK = new Object();
    /** 无锁读取：输入回调里不再为了查表加锁。 */
    private static final Map<Long, Long> BLOCKED_STREAMS = new ConcurrentHashMap<>();
    private static final long STREAM_TTL_MS = 5000L;

    /** 最近一次 DOWN 的区域判定结果（给无参的 onQsIntercept 用）。 */
    private static volatile long lastDownAt;
    private static volatile boolean lastDownTopZone;

    private HookLogic() {
    }

    // ------------------------------------------------------------------
    // 入口
    // ------------------------------------------------------------------

    /** 旧架构入口：EdgeBackGestureHandler 的 onMotionEvent 家族（事件可能来自屏幕任意位置）。 */
    static boolean onEdgeTouch(Object handler, MotionEvent ev) {
        return gate(handler, ev, KIND_EDGE, false, false);
    }

    /**
     * 新架构入口（A16/A17）：DisplayBackGestureHandlerImpl 通过 InputMonitor 的 gesture monitor
     * 直接收事件。这里**额外**按 Y 坐标判断是否落在底部区 —— 这是"策略 A"的全部内容，
     * 是否会真的收到底部区域的触摸必须由实测计数证明（见 bottomZoneSeen）。
     */
    static boolean onEdgeTouchFromGestureMonitor(Object handler, MotionEvent ev) {
        return gate(handler, ev, KIND_EDGE, true, false);
    }

    /** 底部手势入口（若该版本 SystemUI 里存在 HomeHandle 等）。 */
    static boolean onBottomTouch(Object handler, MotionEvent ev) {
        return gate(handler, ev, KIND_BOTTOM_HANDLE, false, false);
    }

    /**
     * Launcher 进程入口（策略 B）。调用方必须已经在 Launcher 进程里、且用户已显式打开开关，
     * 这里仍然再判一次"起手在底部区"，避免坐标口径不一致时误吞整个屏幕的触摸。
     */
    static boolean onLauncherBottomTouch(Object owner, MotionEvent ev) {
        ModuleConfig cfg = HookRuntime.config;
        if (cfg == null || !cfg.launcherBottom) {
            return false;
        }
        return gate(owner, ev, KIND_LAUNCHER, false, false);
    }

    /**
     * 通知栏下拉入口。
     *
     * @param shadeExpanded 通知栏当前是否已经展开；true 时一律放行（否则用户没法点通知）。
     */
    static boolean gateShadeTouch(Object owner, MotionEvent ev, boolean shadeExpanded) {
        return gate(owner, ev, KIND_SHADE, false, shadeExpanded);
    }

    /**
     * 无参的 onQsIntercept() 没有 MotionEvent，只能靠"最近一次 DOWN 落在顶部区"来回答。
     *
     * 注意这里**重新查一次规则**，而不是依赖"上一次 DOWN 已经被拦过"：
     * 如果某个 ROM 上的 DOWN 没有经过我们挂到的任何方法（只挂到了 onQsIntercept），
     * 那么"先有 block 才拦"会让这条兜底路径永远失效。
     *
     * 3 秒内有效，避免用到很久以前的手势；调用方（ShadeHooks）还会再叠加一次
     * "通知栏未展开"的检查。
     */
    static boolean shouldBlockCurrentDown() {
        if (!lastDownTopZone) {
            return false;
        }
        if (SystemClock.elapsedRealtime() - lastDownAt > 3000L) {
            return false;
        }
        return decisionForForeground().blockTopShade;
    }

    /** finishInputEvent 失败时撤销本条流的屏蔽标记，避免把后续事件也一起吞掉。 */
    static void abortStream(MotionEvent ev) {
        if (ev == null) {
            return;
        }
        try {
            for (int kind = KIND_EDGE; kind <= KIND_LAUNCHER; kind++) {
                BLOCKED_STREAMS.remove(streamKey(ev, kind));
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // 闸门
    // ------------------------------------------------------------------

    private static boolean gate(Object owner, MotionEvent ev, int kind,
                                boolean trustedEdge, boolean shadeExpanded) {
        if (ev == null) {
            return false;
        }
        final int action = ev.getActionMasked();
        final ModuleConfig cfg = HookRuntime.config == null ? ModuleConfig.DEFAULT : HookRuntime.config;
        final int height = Zones.screenHeight();
        if (action == MotionEvent.ACTION_DOWN) {
            Zones.maybeRefresh(ev.getRawY());
        }
        // 坐标系说明：rawY 是屏幕坐标；getY() 是 view 局部坐标。
        // 顶部/底部判断统一用 rawY，这样 View 层（shade）和输入层（gesture monitor）口径一致。
        final float rawY = ev.getRawY();
        final boolean topZone = Zones.inTopZone(rawY, height, cfg.topZoneRatio);
        final boolean bottomZone = Zones.inBottomZone(rawY, height, cfg.bottomZoneRatio);

        if (action == MotionEvent.ACTION_DOWN) {
            lastDownAt = SystemClock.elapsedRealtime();
            lastDownTopZone = topZone;
        }

        RuleSet.Decision decision = decisionForForeground();
        final boolean observe = HookRuntime.observeZones;

        // ---- 「游戏模式」会话总闸（armed）----
        // armed 只对「顶部下拉 + 底部上滑」生效：进入后对**所有**前台应用生效（用完即走），
        // 左/右返回仍然按 per-app 规则常驻（保持旧用法完全不变）。
        // 两条安全阀：前台必须可知（拿不到就不拦，fail-open）；前台是本模块控制器时不拦
        // （否则用户永远进不了 UI 去关掉它）。
        ForegroundTracker.Fg fg = currentForeground();
        final boolean armed = ArmController.isArmed()
                && fg != null
                && !ModuleContract.MODULE_PACKAGE.equals(fg.packageName);
        final boolean blockTop = decision.blockTopShade || armed;
        final boolean blockBottom = decision.blockBottom || armed;
        final boolean anyBlock = blockTop || blockBottom
                || decision.blockLeft || decision.blockRight;

        // 观测计数：只有"底部/顶部规则存在"或"验证模式"时才走这里，
        // 平时连 rawY 都不读 —— 这是"非目标应用等于模块没启动"的一部分。
        if (action == MotionEvent.ACTION_DOWN && observe) {
            countObservation(kind, topZone, bottomZone);
        }

        if (!anyBlock) {
            if (observe && action == MotionEvent.ACTION_DOWN && HookRuntime.verifyMode()) {
                logDown(kind, "放行(前台未命中规则)", rawY, height, topZone, bottomZone, false);
            }
            return false;
        }

        final long key = streamKey(ev, kind);
        final long now = SystemClock.uptimeMillis();
        synchronized (LOCK) {
            expire(now);
            if (action == MotionEvent.ACTION_DOWN) {
                boolean block = false;
                String label = null;
                if (kind == KIND_SHADE) {
                    if (shadeExpanded) {
                        label = "放行(通知栏已展开)";
                    } else if (blockTop && topZone) {
                        block = true;
                        label = "TOP-SHADE";
                    } else if (!blockTop) {
                        label = "放行(未勾选顶部下拉)";
                    } else {
                        label = "放行(不在顶部区)";
                    }
                } else if (kind == KIND_EDGE) {
                    int side;
                    if (trustedEdge) {
                        int width = EdgeMetrics.displayWidth(owner);
                        side = ev.getX() < width / 2f
                                ? EdgeMetrics.SIDE_LEFT : EdgeMetrics.SIDE_RIGHT;
                    } else {
                        side = EdgeMetrics.sideOf(owner, ev);
                    }
                    if (bottomZone && blockBottom) {
                        block = true;
                        label = "BOTTOM(返回通道)";
                    } else if (side == EdgeMetrics.SIDE_LEFT && decision.blockLeft) {
                        block = true;
                        label = "LEFT";
                    } else if (side == EdgeMetrics.SIDE_RIGHT && decision.blockRight) {
                        block = true;
                        label = "RIGHT";
                    } else {
                        label = "放行(side=" + side + ",bottom=" + bottomZone + ")";
                    }
                } else if (kind == KIND_LAUNCHER) {
                    if (bottomZone && blockBottom) {
                        block = true;
                        label = "BOTTOM(Launcher)";
                    } else {
                        label = "放行(不在底部区或未勾选底部)";
                    }
                } else {
                    if (blockBottom) {
                        block = true;
                        label = "BOTTOM(handle)";
                    } else {
                        label = "放行(未勾选底部)";
                    }
                }
                if (block) {
                    BLOCKED_STREAMS.put(key, now);
                    if (kind == KIND_EDGE && bottomZone) {
                        HookRuntime.bottomZoneBlocked++;
                    }
                    if (kind == KIND_LAUNCHER && bottomZone) {
                        HookRuntime.bottomZoneBlocked++;
                    }
                    if (kind == KIND_SHADE) {
                        HookRuntime.shadeBlocked++;
                    }
                    blockLog(label, kind, rawY, height, topZone, bottomZone, armed);
                } else if (observe && HookRuntime.verifyMode()) {
                    logDown(kind, label, rawY, height, topZone, bottomZone, false);
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

    // ------------------------------------------------------------------
    // 观测与日志
    // ------------------------------------------------------------------

    private static void countObservation(int kind, boolean topZone, boolean bottomZone) {
        try {
            if (topZone) {
                HookRuntime.topZoneSeen++;
            }
            if (kind == KIND_EDGE || kind == KIND_LAUNCHER) {
                if (bottomZone) {
                    HookRuntime.bottomZoneSeen++;
                }
            }
            if (kind == KIND_SHADE) {
                HookRuntime.shadeSeen++;
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 验证模式日志：一次性输出判定所需的**全部**字段，
     * 用户先看这行确认坐标判断对不对，再关掉验证模式开拦截。
     */
    private static void logDown(int kind, String label, float rawY, int height,
                                boolean topZone, boolean bottomZone, boolean blocked) {
        long now = SystemClock.uptimeMillis();
        if (now - lastDiagAt < 250L) {
            return;
        }
        lastDiagAt = now;
        try {
            Xlog.step("ZONE " + kindName(kind)
                    + " action=DOWN"
                    + " rawY=" + (int) rawY
                    + " screenHeight=" + height
                    + " inTopZone=" + topZone
                    + " inBottomZone=" + bottomZone
                    + " fg=" + fgPackage()
                    + " blocked=" + blocked
                    + " -> " + label);
        } catch (Throwable ignored) {
        }
    }

    private static void blockLog(String label, int kind, float rawY, int height,
                                 boolean topZone, boolean bottomZone, boolean armed) {
        long now = SystemClock.uptimeMillis();
        if (now - lastBlockLogAt < 2000L) {
            return;
        }
        lastBlockLogAt = now;
        Xlog.step("BLOCK " + label + " [" + kindName(kind) + "]"
                + " rawY=" + (int) rawY + "/" + height
                + " inTopZone=" + topZone + " inBottomZone=" + bottomZone
                + " armed=" + armed
                + " fg=" + fgPackage());
    }

    private static String kindName(int kind) {
        if (kind == KIND_EDGE) {
            return "EDGE";
        }
        if (kind == KIND_SHADE) {
            return "SHADE";
        }
        if (kind == KIND_LAUNCHER) {
            return "LAUNCHER";
        }
        return "BOTTOM";
    }

    private static long lastDiagAt;
    private static long lastBlockLogAt;

    private static String fgPackage() {
        try {
            // 日志路径同样只用缓存：绝不因为"要打一行日志"而去触发 Binder 查询
            ForegroundTracker.Fg fg = ForegroundTracker.cachedOnly();
            return fg == null || fg.packageName == null ? "?" : fg.packageName;
        } catch (Throwable t) {
            return "?";
        }
    }

    // ------------------------------------------------------------------
    // 规则查询
    // ------------------------------------------------------------------

    /**
     * 触摸回调里只做：读一次 volatile 规则引用 + 一次 map 查询。
     * 前台检测本身有 3s 缓存 + 推送式更新，不会每次触摸都走 Binder。
     */
    static RuleSet.Decision decisionForForeground() {
        try {
            RuleSet rules = HookRuntime.rules;
            if (rules == null || rules.size() == 0) {
                return RuleSet.Decision.NONE;
            }
            ForegroundTracker.Fg fg = currentForeground();
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

    /**
     * 当前前台应用。
     * 有推送式跟踪时只用缓存（触摸线程绝不阻塞在 Binder 上）；
     * 没装成 TaskStackListener 时退回按需查询（3s 缓存 + 失败冷却）。
     */
    static ForegroundTracker.Fg currentForeground() {
        try {
            return ForegroundTracker.isTracking()
                    ? ForegroundTracker.cachedOnly()
                    : ForegroundTracker.get();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 返回真正触发时的拦截（A16/A17 最可靠的一道）。
     *
     * DisplayBackGestureHandlerImpl 的输入链只是一半：手势达标后系统会回调
     * EdgeBackGestureHandler 的 BackCallback（A17 是 \$5#triggerBack），那里才是"执行返回"的地方。
     * 这里按 mIsOnLeftEdge 判断左右，再查一次规则，命中就不调用原方法。
     *
     * 注意：这条路径与"底部上滑"无关（底部由 Launcher 执行），
     * 所以这里只看 blockLeft / blockRight，不受 blockBottom / blockTopShade 影响。
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
            Xlog.debug("backInvoked: left=" + onLeftEdge + " block=" + block
                    + " fg=" + describeForeground());
            return block;
        } catch (Throwable t) {
            Xlog.step("backInvoked check failed: " + t);
            return false;
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

    // ------------------------------------------------------------------
    // 事件流跟踪
    // ------------------------------------------------------------------

    private static long streamKey(MotionEvent ev, int kind) {
        // 只依赖公开 API：downTime 在一次手势（含多指）内唯一。
        // （MotionEvent.getDisplayId() 是 @hide，android.jar 里没有，不能直接调用。）
        return ev.getDownTime() * 8L + kind;
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
