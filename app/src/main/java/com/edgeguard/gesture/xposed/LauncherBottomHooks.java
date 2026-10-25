package com.edgeguard.gesture.xposed;

import android.view.InputEvent;
import android.view.MotionEvent;

import com.edgeguard.gesture.common.Logx;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 策略 B：Launcher 进程内的底部上滑屏蔽（**实验性，默认关闭**）。
 *
 * 事实前提（AOSP main 核对）：AOSP 16/17 的「上滑回桌面 / 上滑进最近任务」由
 * com.android.quickstep.TouchInteractionService（在 Launcher 进程）通过
 * InputManager.monitorGestureInput() 接收并执行，SystemUI 进程里没有最终执行逻辑。
 *
 * 挂在哪一层（这一点决定了策略 B 到底能不能用）：
 *
 *   错误做法：挂 TouchInteractionService#onInputEvent(InputEvent)
 *     —— 它只是 getInputReceiver(looper, choreographer, this::onInputEvent) 里的
 *        **listener**，类本身 extends Service，**拿不到 finishInputEvent**；
 *        消费不了事件，只能记日志。
 *   正确做法：挂 InputChannelCompat$InputEventReceiver$1#onInputEvent
 *     —— 真正持有 finishInputEvent 的那一层（Launcher 进程里同样存在）。
 *
 * 所以 install() 的顺序是：**先挂包装 receiver（主力）→ 再挂 TIS 自身（兜底探测）**。
 * 两套 Hook 各自独立判断，任一条命中即可吞掉手势；都失败就只是"没效果"，不会崩。
 *
 * 生效还需要用户侧两个显式动作（缺一不可）：
 *   1) LSPosed 里把作用域额外勾选当前桌面/Launcher 包名；
 *   2) 模块 UI「阈值/验证」里勾选 "Launcher 底部 Hook（策略 B）"，或开启游戏模式。
 */
final class LauncherBottomHooks {

    private static final Set<String> HOOKED = new HashSet<>();
    private static volatile boolean warnedNoFinish;
    private static volatile boolean loggedListener;

    private LauncherBottomHooks() {
    }

    static int install(ClassLoader classLoader) {
        int total = 0;

        // ① 主力：InputChannelCompat 的包装 receiver（唯一能安全消费事件的一层）
        try {
            List<Class<?>> receivers = Targets.loadInputChannelReceivers(classLoader);
            if (receivers.isEmpty()) {
                Logx.w("[LAUNCHER] 未找到 InputChannelCompat receiver：本 ROM 的底部手势入口可能不同，"
                        + "策略 B 大概率不可用（会退回只挂 TIS listener）");
            }
            for (Class<?> cls : receivers) {
                total += hookReceiverClass(cls);
            }
        } catch (Throwable t) {
            Logx.e("[LAUNCHER] hook receivers failed", t);
        }

        // ② 兜底：TouchInteractionService 及其内部类的触摸方法（多半是 listener，不能消费）
        try {
            List<Class<?>> services = Targets.loadAll(classLoader, Targets.LAUNCHER_TOUCH_CLASSES);
            List<Class<?>> expanded = new ArrayList<>(services);
            for (Class<?> cls : services) {
                try {
                    for (Class<?> inner : cls.getDeclaredClasses()) {
                        if (inner != null && !expanded.contains(inner)) {
                            expanded.add(inner);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            for (Class<?> cls : expanded) {
                total += hookListenerClass(cls);
            }
        } catch (Throwable t) {
            Logx.e("[LAUNCHER] hook services failed", t);
        }

        if (total == 0) {
            Logx.w("[LAUNCHER] 未挂载任何底部手势入口（类名可能与本 ROM 不同）。"
                    + "此功能按设计等于关闭；请把 logcat 里 [LAUNCHER] 行贴出来补候选名。");
        } else {
            Logx.i("[LAUNCHER] 底部手势入口已挂载: " + total + " 个");
        }
        return total;
    }

    // ------------------------------------------------------------------
    // ① 包装 receiver（能 finishInputEvent）
    // ------------------------------------------------------------------

    private static int hookReceiverClass(Class<?> cls) {
        int count = 0;
        Method[] methods;
        try {
            methods = cls.getDeclaredMethods();
        } catch (Throwable t) {
            return 0;
        }
        for (Method m : methods) {
            if (!"onInputEvent".equals(m.getName())) {
                continue;
            }
            boolean carries = false;
            for (Class<?> p : m.getParameterTypes()) {
                if (InputEvent.class.isAssignableFrom(p)) {
                    carries = true;
                    break;
                }
            }
            if (!carries) {
                continue;
            }
            String signature = Reflect.signature(m);
            String key = "LAUNCHER-RCVR|" + signature;
            if (HOOKED.contains(key)) {
                continue;
            }
            try {
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new LauncherReceiverHook(signature));
                HOOKED.add(key);
                count++;
                Logx.i("hooked [LAUNCHER-RCVR] " + signature
                        + " returns " + m.getReturnType().getSimpleName());
            } catch (Throwable t) {
                Logx.w("hook failed: " + signature, t);
            }
        }
        return count;
    }

    private static final class LauncherReceiverHook extends XC_MethodHook {

        private final String signature;

        LauncherReceiverHook(String signature) {
            this.signature = signature;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                InputEvent input = extractInputEvent(param.args);
                if (!(input instanceof MotionEvent)) {
                    return;
                }
                // 同一条通道可能被 Launcher 里别的 monitor 复用，按 listener 归属过滤
                Object listener = Reflect.fieldValue(param.thisObject, "val$listener");
                if (!isLauncherListener(listener)) {
                    return;
                }
                MotionEvent event = (MotionEvent) input;
                if (!HookLogic.onLauncherBottomTouch(param.thisObject, event)) {
                    return;
                }
                if (InputSink.finish(param.thisObject, input, true)) {
                    InputSink.consumeReturn(param);
                    Xlog.debug("[LAUNCHER] consumed bottom gesture via receiver (action="
                            + event.getActionMasked() + ")");
                } else {
                    HookLogic.abortStream(event);
                    warnNoFinish();
                }
            } catch (Throwable t) {
                Logx.w("[LAUNCHER-RCVR] callback failed: " + signature + " (放行原逻辑)", t);
            }
        }
    }

    // ------------------------------------------------------------------
    // ② TIS 自身的 listener（拿不到 finishInputEvent，只作为兜底）
    // ------------------------------------------------------------------

    private static int hookListenerClass(Class<?> cls) {
        int count = 0;
        List<Targets.TouchMethod> methods;
        try {
            methods = Targets.collectTouchMethods(cls, Targets.LAUNCHER_TOUCH_METHODS);
        } catch (Throwable t) {
            return 0;
        }
        for (Targets.TouchMethod cand : methods) {
            String signature = cand.signature();
            String key = "LAUNCHER|" + signature;
            if (HOOKED.contains(key)) {
                continue;
            }
            try {
                XposedBridge.hookMethod(cand.method, new LauncherListenerHook(signature));
                HOOKED.add(key);
                count++;
                Logx.i("hooked [LAUNCHER] " + signature
                        + " returns " + cand.method.getReturnType().getSimpleName());
            } catch (Throwable t) {
                Logx.w("hook failed: " + signature, t);
            }
        }
        return count;
    }

    private static final class LauncherListenerHook extends XC_MethodHook {

        private final String signature;

        LauncherListenerHook(String signature) {
            this.signature = signature;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                InputEvent input = extractInputEvent(param.args);
                if (!(input instanceof MotionEvent)) {
                    return;
                }
                MotionEvent event = (MotionEvent) input;
                if (!HookLogic.onLauncherBottomTouch(param.thisObject, event)) {
                    return;
                }
                // listener 层通常不是 InputEventReceiver：能 finish 就吞，不能就放弃（绝不让输入卡死）
                if (InputSink.finish(param.thisObject, input, true)) {
                    InputSink.consumeReturn(param);
                } else {
                    HookLogic.abortStream(event);
                    warnNoFinish();
                }
            } catch (Throwable t) {
                Logx.w("[LAUNCHER] callback failed: " + signature + " (放行原逻辑)", t);
            }
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /**
     * 这条 receiver 是不是 Launcher 的手势 listener。
     *
     * TIS 传的是方法引用（this::onInputEvent），类名形如
     * com.android.quickstep.TouchInteractionService$$ExternalSyntheticLambdaN；
     * 用 quickstep/launcher 关键字判断，命中不了就放行（保守）。
     */
    private static boolean isLauncherListener(Object listener) {
        try {
            if (listener == null) {
                return false;
            }
            String name = listener.getClass().getName();
            if (!loggedListener) {
                loggedListener = true;
                Logx.i("[LAUNCHER] gesture receiver listener = " + name);
            }
            return name.contains("quickstep")
                    || name.contains("launcher3")
                    || name.contains("Launcher");
        } catch (Throwable t) {
            return false;
        }
    }

    private static void warnNoFinish() {
        if (warnedNoFinish) {
            return;
        }
        warnedNoFinish = true;
        Logx.w("[LAUNCHER] 本次入口拿不到 finishInputEvent（多为 listener），已放弃消费事件。"
                + "若上面没有 [LAUNCHER-RCVR] 挂载行，说明本 ROM 的底部手势只能靠作用域外的方案。");
    }

    private static InputEvent extractInputEvent(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object arg : args) {
            if (arg instanceof InputEvent) {
                return (InputEvent) arg;
            }
        }
        return null;
    }
}
