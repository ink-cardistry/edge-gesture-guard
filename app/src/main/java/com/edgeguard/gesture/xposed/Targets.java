package com.edgeguard.gesture.xposed;

import android.view.InputEvent;
import android.view.MotionEvent;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Hook 目标候选表（唯一的"名字清单"）。
 *
 * 设计约定：
 *  - 这里只放**候选名**，不假设任何一个是存在的；
 *  - 探测不到就由上层的 install() 记日志 + 状态卡降级，绝不抛异常；
 *  - 同一个类里所有"可能是触摸入口"的方法都会挂上（多挂比漏挂安全：
 *    每个回调内部都有自己的坐标/规则判断，误挂到不相关的方法也不会误吞）。
 *
 * ROM 差异怎么处理：先按名字加载，加不上就靠 logcat 里的
 * "shade candidates: ..." 行把真实名字补进来。
 */
final class Targets {

    /**
     * 通知栏（shade）触摸链候选类。
     *
     * 历史沿革（AOSP 12 -> 17）：
     *  - A12/13：com.android.systemui.statusbar.phone.NotificationPanelViewController
     *            + NotificationShadeWindowView（FrameLayout，委托给
     *            InteractionEventHandler / TouchHandler）
     *  - A14/15：NotificationPanelViewController 挪到 com.android.systemui.shade，
     *            触摸逻辑逐步抽到 ShadeTouchEventHandler
     *  - A16/17：scene container 重构，shade 仍由 NotificationShadeWindowView / 
     *            面板控制器接收触摸；shade 的触摸入口可能变成 ShadeTouchEventHandler
     * 所以三个包路径全部作为候选，谁在就用谁，多个都在就都挂。
     */
    static final String[] SHADE_TOUCH_CLASSES = {
            "com.android.systemui.shade.NotificationShadeWindowView",
            "com.android.systemui.shade.NotificationShadeWindowViewController",
            "com.android.systemui.shade.NotificationPanelViewController",
            "com.android.systemui.statusbar.phone.NotificationPanelViewController",
            "com.android.systemui.shade.ShadeTouchEventHandler",
            "com.android.systemui.statusbar.phone.PhoneStatusBarView",
            "com.android.systemui.statusbar.phone.StatusBarTouchHandler",
    };

    /**
     * shade 侧触摸方法名。
     *
     * 关键（AOSP main 核对结果）：NotificationShadeWindowView 自己覆写
     * dispatchTouchEvent / onInterceptTouchEvent / onTouchEvent，并把逻辑委托给嵌套接口
     * InteractionEventHandler；NotificationShadeWindowViewController 通过
     * handleDispatchTouchEvent / shouldInterceptTouchEvent / handleTouchEvent 实现该接口。
     * 所以这两套名字都要挂 —— 只挂 View 的会漏掉控制器的真实逻辑。
     *
     * onQsIntercept：A13 时代的面板方法，A15/16/17 的 AOSP main 里**未证实**仍存在；
     * 探测不到就自然跳过（不抛异常、不假装支持）。
     */
    static final String[] SHADE_TOUCH_METHODS = {
            "onInterceptTouchEvent",
            "onTouchEvent",
            "dispatchTouchEvent",
            "handleDispatchTouchEvent",
            "shouldInterceptTouchEvent",
            "handleTouchEvent",
            "onQsIntercept",
            "handleMotionEvent",
            "onMotionEvent",
    };

    /**
     * Launcher / Quickstep 底部手势入口候选（策略 B，默认关闭）。
     *
     * AOSP 里底部上滑由 com.android.quickstep.TouchInteractionService 通过
     * InputManager.monitorGestureInput() 拿到手势通道后自己消费，**不在 SystemUI 进程**。
     * TouchInteractionService 既可能是 InputEventReceiver 的宿主（能 finishInputEvent），
     * 也可能只是个 listener —— 后者无法安全消费事件，届时本模块会明确放弃（见 LauncherBottomHooks）。
     */
    static final String[] LAUNCHER_TOUCH_CLASSES = {
            "com.android.quickstep.TouchInteractionService",
            "com.android.launcher3.uioverrides.TouchInteractionService",
            "com.android.quickstep.inputconsumers.OtherActivityInputConsumer",
    };

    static final String[] LAUNCHER_TOUCH_METHODS = {
            "onInputEvent",
            "onMotionEvent",
            "handleMotionEvent",
            "dispatchTouchEvent",
            "onTouchEvent",
    };

    /** 一个候选触摸方法。carriesEvent=false 表示它不带 MotionEvent（如无参 onQsIntercept）。 */
    static final class TouchMethod {
        final Method method;
        final boolean carriesEvent;

        TouchMethod(Method method, boolean carriesEvent) {
            this.method = method;
            this.carriesEvent = carriesEvent;
        }

        String signature() {
            return Reflect.signature(method);
        }
    }

    private Targets() {
    }

    /** 按候选名加载，返回实际存在的类（顺序保持候选顺序）。 */
    static List<Class<?>> loadAll(ClassLoader cl, String[] names) {
        List<Class<?>> out = new ArrayList<>();
        if (names == null) {
            return out;
        }
        for (String name : names) {
            Class<?> cls = Reflect.load(cl, name);
            if (cls != null && !out.contains(cls)) {
                out.add(cls);
            }
        }
        return out;
    }

    /**
     * 找 InputChannelCompat 的包装 receiver 类（真正持有 finishInputEvent 的那一层）。
     *
     * 这条对「策略 B」是决定性的：AOSP 的 TouchInteractionService 只是
     *   mInputMonitorCompat.getInputReceiver(looper, choreographer, this::onInputEvent)
     * 里的 **listener**，它自己 extends Service、拿不到 finishInputEvent；
     * 真正能消费事件的是 InputChannelCompat 内部那个匿名 InputEventReceiver 子类。
     * 所以 Launcher 进程里必须挂这个包装类的 onInputEvent，而不是挂在 TIS 上。
     */
    static List<Class<?>> loadInputChannelReceivers(ClassLoader cl) {
        List<Class<?>> out = new ArrayList<>();
        Class<?> direct = Reflect.load(cl,
                "com.android.systemui.shared.system.InputChannelCompat$InputEventReceiver$1");
        if (direct != null) {
            out.add(direct);
        }
        Class<?> wrapper = Reflect.load(cl,
                "com.android.systemui.shared.system.InputChannelCompat$InputEventReceiver");
        if (wrapper != null) {
            try {
                for (Class<?> c : wrapper.getDeclaredClasses()) {
                    if (c != null && !out.contains(c) && Reflect.field(c, "val$listener") != null) {
                        out.add(c);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    static String simpleName(String className) {
        int index = className == null ? -1 : className.lastIndexOf('.');
        return index < 0 ? String.valueOf(className) : className.substring(index + 1);
    }

    /**
     * 收集一个类（含父类，最多 8 层）里名字命中 names 的触摸入口。
     *
     * 两条硬约束（本模块踩过的坑）：
     *  1) 跳过 android.* / androidx.* 等框架共享实现 —— 挂 View.onTouchEvent 会影响 SystemUI 全部控件；
     *  2) 跳过 static / abstract。
     */
    static List<TouchMethod> collectTouchMethods(Class<?> cls, String[] names) {
        List<TouchMethod> out = new ArrayList<>();
        if (cls == null || names == null) {
            return out;
        }
        Class<?> c = cls;
        int guard = 0;
        while (c != null && c != Object.class && guard++ < 8) {
            Method[] methods;
            try {
                methods = c.getDeclaredMethods();
            } catch (Throwable t) {
                break;
            }
            for (Method m : methods) {
                try {
                    if (!matchesName(m.getName(), names)) {
                        continue;
                    }
                    int mod = m.getModifiers();
                    if (Modifier.isStatic(mod) || Modifier.isAbstract(mod)) {
                        continue;
                    }
                    if (!Reflect.isRomDeclared(m.getDeclaringClass())) {
                        continue;
                    }
                    boolean carries = false;
                    for (Class<?> p : m.getParameterTypes()) {
                        if (MotionEvent.class.isAssignableFrom(p) || InputEvent.class.isAssignableFrom(p)) {
                            carries = true;
                            break;
                        }
                    }
                    // 无参版本（历史上的 onQsIntercept()）也允许挂：回调里用"最近一次 DOWN"判断。
                    if (!carries && m.getParameterTypes().length > 0) {
                        continue;
                    }
                    if (!carries && !"onQsIntercept".equals(m.getName())) {
                        continue;
                    }
                    m.setAccessible(true);
                    out.add(new TouchMethod(m, carries));
                } catch (Throwable ignored) {
                    // 单个方法失败不影响其它方法
                }
            }
            c = c.getSuperclass();
        }
        return out;
    }

    private static boolean matchesName(String name, String[] names) {
        if (name == null) {
            return false;
        }
        for (String candidate : names) {
            if (candidate.equals(name)) {
                return true;
            }
        }
        return false;
    }
}
