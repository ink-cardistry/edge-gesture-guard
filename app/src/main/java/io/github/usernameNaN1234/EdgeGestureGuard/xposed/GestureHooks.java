package io.github.usernameNaN1234.EdgeGestureGuard.xposed;

import android.view.InputEvent;
import android.view.MotionEvent;

import io.github.usernameNaN1234.EdgeGestureGuard.common.Logx;
import io.github.usernameNaN1234.EdgeGestureGuard.common.StatusReport;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Hook 安装器（双架构支持）。
 *
 * A12 ~ A15（旧架构）
 *   EdgeBackGestureHandler 有 onMotionEvent(MotionEvent)，事件由 NavigationBar 的 view 层分发，
 *   可能是屏幕任意位置 -> 需要按反射读到的边宽判断左右。
 *
 * A16 / A17（新架构，本机实测）
 *   EdgeBackGestureHandler 已经**没有 onMotionEvent**，只剩 cancelGesture / dispatchToBackAnimation；
 *   真正的触摸入口变成：
 *       DisplayBackGestureHandlerImpl$inputEventReceiver$1.onInputEvent(InputEvent)
 *   （DisplayBackGestureHandlerImpl 通过 InputMonitorCompat 的 gesture monitor 直接拿事件）。
 *   这条通道收到的事件本身就在返回手势区域，所以按屏幕左右半区判定即可。
 *
 *   注意：新架构里 InputEventReceiver 的事件必须由处理方 finishInputEvent，
 *   否则输入通道永远等不到"已处理"，会出现手势后触摸卡死/ANR。
 *   因此屏蔽时必须先 finishInputEvent(event, true) 再阻止原逻辑；
 *   finish 失败就放弃本次屏蔽（宁可不生效，也绝不阻断输入）。
 *
 * 所有探测/挂载/回调都在 try/catch 内；任何异常都退回系统默认行为。
 */
final class GestureHooks {

    private static final int ROLE_EDGE = 1;
    private static final int ROLE_BOTTOM = 2;

    /** 已挂载的签名，避免同一个方法被挂两次。 */
    private static final Set<String> HOOKED = new HashSet<>();

    /** 新架构候选类 -> 挂载数量，写进状态卡方便远程排障。 */
    private static volatile String MODERN_DETAIL = "";

    private GestureHooks() {
    }

    static void install(ClassLoader classLoader) {
        StatusReport status = HookRuntime.status;
        status.moduleVersion = "1.8.1";

        Class<?> edgeClass = null;
        try {
            edgeClass = Probe.findEdgeHandler(classLoader);
        } catch (Throwable t) {
            Logx.e("probe edge handler failed", t);
        }
        if (edgeClass != null) {
            try {
                EdgeMetrics.bind(edgeClass);
                status.edgeMetrics = EdgeMetrics.summary();
            } catch (Throwable t) {
                Logx.w("EdgeMetrics.bind failed", t);
            }
        }

        int legacyCount = installLegacyEdge(edgeClass);
        int modernCount = installModernBackGesture(classLoader, edgeClass);
        int backCallbackCount = 0;
        try {
            backCallbackCount = BackInvocationGuard.install(edgeClass);
        } catch (Throwable t) {
            Xlog.step("BackInvocationGuard.install failed: " + t);
        }

        // 导航栏"临时滑出"抑制：实测在 LineageOS 24 上无法可靠做到
        // （transient 显示由 system_server 的 DisplayPolicy 发起；1.6.2/1.6.4 的几种挂法
        //   要么抓不到目标视图，要么误藏通知行）。因此默认**不安装**，避免误伤。
        // 需要时把下面的开关改 true 自行试验（NavBarHooks 代码保留）。
        int navCount = 0;
        final boolean NAV_HOOK_ENABLED = false;
        if (NAV_HOOK_ENABLED) {
            try {
                navCount = NavBarHooks.install(classLoader);
            } catch (Throwable t) {
                Logx.e("NavBarHooks.install failed", t);
            }
        }

        // 顶部状态栏隐藏（游戏模式期间）：只针对 PhoneStatusBarView，可控可还原
        int sbCount = 0;
        try {
            sbCount = StatusBarHider.install(classLoader);
        } catch (Throwable t) {
            Logx.e("StatusBarHider.install failed", t);
        }

        // 功能二：通知栏下拉屏蔽（仍在 SystemUI 进程内，独立于返回手势 Hook）
        int shadeCount = 0;
        try {
            shadeCount = ShadeHooks.install(classLoader, status);
        } catch (Throwable t) {
            Logx.e("ShadeHooks.install failed", t);
            Xlog.step("ShadeHooks.install FAILED: " + t);
        }

        HookRuntime.legacyHooks = legacyCount;
        HookRuntime.modernHooks = modernCount;
        status.edgeHooked = legacyCount > 0 || modernCount > 0;
        StringBuilder target = new StringBuilder();
        target.append("旧架构 onMotionEvent: ").append(legacyCount).append(" 个入口");
        if (edgeClass != null) {
            target.append(" (").append(edgeClass.getSimpleName()).append(")");
        }
        target.append("\n新架构 InputEventReceiver: ").append(modernCount).append(" 个入口");
        target.append("\n返回触发拦截 (onBackInvoked): ").append(backCallbackCount).append(" 个入口");
        target.append("\n通知栏下拉 (shade 触摸链): ").append(shadeCount).append(" 个入口");
        target.append("\n导航栏滑出抑制 (transitionTo): ").append(navCount).append(" 个入口");
        if (MODERN_DETAIL.length() > 0) {
            target.append("\n候选: ").append(MODERN_DETAIL);
        }
        status.edgeTarget = target.toString();
        if (!status.edgeHooked) {
            Logx.e("左右返回 Hook 未挂载：旧/新两种入口都没找到。请把 probe: / edge handler methods "
                    + "日志行贴出来，可据此补充候选名。");
        }

        installBottom(classLoader, status);
        // 无论后续 IPC 初始化是否成功，先把探测结果落到 Settings.Global（App 读它不需要权限）
        try {
            Diag.write(Ctx.any());
        } catch (Throwable t) {
            Logx.w("diag after install failed", t);
        }
        Xlog.step("hooks installed: edge=" + status.edgeHooked
                + ", legacy=" + legacyCount + ", modern=" + modernCount
                + ", backCallback=" + backCallbackCount
                + ", shade=" + status.shadeState + "(" + shadeCount + ")"
                + ", navbar=" + navCount + ", statusbar=" + sbCount
                + ", bottom=" + status.bottomState
                + ", zones[" + HookRuntime.config.describe() + "]");
        Logx.i("hook install finished: edge=" + status.edgeHooked
                + " (legacy=" + legacyCount + ", modern=" + modernCount + ")"
                + " bottom=" + status.bottomState);
    }

    // ------------------------------------------------------------------
    // 旧架构：MotionEvent 入口
    // ------------------------------------------------------------------

    private static int installLegacyEdge(Class<?> handlerClass) {
        if (handlerClass == null) {
            return 0;
        }
        try {
            int count = hookTouchEntries(handlerClass, ROLE_EDGE);
            Logx.i("legacy edge hooks on " + handlerClass.getName() + ": " + count);
            return count;
        } catch (Throwable t) {
            Logx.e("legacy edge hook failed", t);
            return 0;
        }
    }

    // ------------------------------------------------------------------
    // 新架构：InputEventReceiver.onInputEvent
    // ------------------------------------------------------------------

    private static int installModernBackGesture(ClassLoader classLoader, Class<?> edgeHandlerClass) {
        try {
            Class<?> implClass = Probe.findBackGestureImpl(classLoader, edgeHandlerClass);
            if (implClass == null) {
                Logx.i("no DisplayBackGestureHandlerImpl (旧架构或类名不同)");
                return 0;
            }
            Logx.i("modern back-gesture impl: " + implClass.getName()
                    + ", declaredClasses=" + describeDeclaredClasses(implClass));
            int count = 0;
            StringBuilder detail = new StringBuilder();
            for (Class<?> receiverClass : inputReceiverCandidates(classLoader, implClass)) {
                int hooked = hookInputEventReceivers(receiverClass);
                count += hooked;
                detail.append(receiverClass.getSimpleName()).append('=').append(hooked).append(' ');
            }
            MODERN_DETAIL = detail.toString().trim();
            Logx.i("modern back-gesture input hooks: " + count + " (" + MODERN_DETAIL + ")");
            return count;
        } catch (Throwable t) {
            Logx.e("modern back gesture hook failed", t);
            return 0;
        }
    }

    /**
     * 找"真正能 finishInputEvent"的 receiver 类。
     *
     * 实测（LineageOS 24 / Android 17）：
     *   DisplayBackGestureHandlerImpl.inputEventReceiver 的类型是
     *   InputChannelCompat$InputEventReceiver（包装类），它内部的匿名子类
     *   InputChannelCompat$InputEventReceiver$1 extends android.view.InputEventReceiver，
     *   持有 val$listener 字段并声明 onInputEvent(InputEvent)。
     *   而 DisplayBackGestureHandlerImpl$inputEventReceiver$1 只是 listener，
     *   **没有** finishInputEvent，挂它一定失败（旧版就是这样）。
     */
    private static List<Class<?>> inputReceiverCandidates(ClassLoader classLoader, Class<?> implClass) {
        List<Class<?>> out = new ArrayList<>();
        Class<?> direct = Reflect.load(classLoader,
                "com.android.systemui.shared.system.InputChannelCompat$InputEventReceiver$1");
        if (direct != null) {
            out.add(direct);
        }
        // 兜底：从包装类里找声明了 onInputEvent + val$listener 的匿名子类
        Class<?> wrapper = Reflect.load(classLoader,
                "com.android.systemui.shared.system.InputChannelCompat$InputEventReceiver");
        if (wrapper != null) {
            try {
                for (Class<?> c : wrapper.getDeclaredClasses()) {
                    if (c == null || out.contains(c)) {
                        continue;
                    }
                    if (Reflect.field(c, "val$listener") != null) {
                        out.add(c);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private static int hookInputEventReceivers(Class<?> cls) {
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
            Class<?>[] params = m.getParameterTypes();
            boolean carriesInput = false;
            for (Class<?> p : params) {
                if (InputEvent.class.isAssignableFrom(p)) {
                    carriesInput = true;
                    break;
                }
            }
            if (!carriesInput) {
                continue;
            }
            String signature = Reflect.signature(m);
            String key = "IN|" + signature;
            if (HOOKED.contains(key)) {
                continue;
            }
            try {
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new InputEventHook(signature));
                HOOKED.add(key);
                count++;
                Logx.i("hooked [EDGE-INPUT] " + signature
                        + " returns " + m.getReturnType().getSimpleName());
            } catch (Throwable t) {
                Logx.w("hook failed: " + signature, t);
            }
        }
        return count;
    }

    private static String describeDeclaredClasses(Class<?> cls) {
        try {
            Class<?>[] declared = cls.getDeclaredClasses();
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < declared.length; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(declared[i].getSimpleName());
            }
            return sb.append(']').toString();
        } catch (Throwable t) {
            return "?";
        }
    }

    // ------------------------------------------------------------------
    // 底部手势（探测不到就明确降级，不假装支持）
    // ------------------------------------------------------------------

    private static void installBottom(ClassLoader classLoader, StatusReport status) {
        try {
            Class<?> homeClass = Probe.findHomeHandle(classLoader);
            if (homeClass == null) {
                status.bottomState = StatusReport.BOTTOM_UNSUPPORTED;
                status.bottomTarget = "未探测到 SystemUI 侧底部手势入口（HomeHandle 等）";
                Logx.w("bottom gesture entry NOT found. AOSP 的底部上滑由 Launcher3/Quickstep "
                        + "的 TouchInteractionService 通过 InputManager 手势通道接收，不在 SystemUI 进程；"
                        + "本模块按约束只 Hook System UI，因此底部屏蔽在此环境不可用（降级为仅左右返回）。");
                return;
            }
            int count = hookTouchEntries(homeClass, ROLE_BOTTOM);
            status.bottomState = count > 0 ? StatusReport.BOTTOM_ATTACHED : StatusReport.BOTTOM_UNSUPPORTED;
            status.bottomTarget = homeClass.getName() + "（" + count + " 个触摸入口）";
            Logx.i("bottom hook: " + status.bottomState + " " + status.bottomTarget);
        } catch (Throwable t) {
            status.bottomState = StatusReport.BOTTOM_UNSUPPORTED;
            status.bottomTarget = "安装失败: " + t.getClass().getSimpleName() + ": " + t.getMessage();
            Logx.e("bottom hook install failed", t);
        }
    }

    // ------------------------------------------------------------------
    // 通用：方法挂载 + 结果消费
    // ------------------------------------------------------------------

    private static int hookTouchEntries(Class<?> cls, int role) {
        int count = 0;
        List<Method> methods = Reflect.touchEntryMethods(cls);
        Logx.i((role == ROLE_EDGE ? "edge" : "bottom") + " candidates on "
                + cls.getSimpleName() + ": " + methods.size());
        for (Method m : methods) {
            String signature = Reflect.signature(m);
            String key = role + "|" + signature;
            if (HOOKED.contains(key)) {
                continue;
            }
            try {
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new TouchHook(role, signature));
                HOOKED.add(key);
                count++;
                Logx.i("hooked [" + (role == ROLE_EDGE ? "EDGE" : "BOTTOM") + "] " + signature
                        + " returns " + m.getReturnType().getSimpleName());
            } catch (Throwable t) {
                Logx.w("hook failed: " + signature, t);
            }
        }
        return count;
    }

    /**
     * 旧架构 Hook：未屏蔽 -> 什么都不做；屏蔽 -> 不执行原方法并返回"已消费"。
     */
    private static final class TouchHook extends XC_MethodHook {

        private final int role;
        private final String signature;

        TouchHook(int role, String signature) {
            this.role = role;
            this.signature = signature;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                MotionEvent event = extractMotionEvent(param.args);
                if (event == null) {
                    return;
                }
                boolean consume = role == ROLE_EDGE
                        ? HookLogic.onEdgeTouch(param.thisObject, event)
                        : HookLogic.onBottomTouch(param.thisObject, event);
                if (consume) {
                    consumeEvent(param);
                }
            } catch (Throwable t) {
                Logx.w("hook callback failed: " + signature + " (放行原逻辑)", t);
            }
        }
    }

    /**
     * 新架构 Hook：InputEventReceiver.onInputEvent。
     * 屏蔽前必须先 finishInputEvent(event, true)，否则输入通道会卡住。
     */
    private static final class InputEventHook extends XC_MethodHook {

        private final String signature;

        InputEventHook(String signature) {
            this.signature = signature;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                InputEvent input = extractInputEvent(param.args);
                if (!(input instanceof MotionEvent)) {
                    return;
                }
                // 只处理"返回手势"这条 InputChannelCompat 通道：
                // 导航条 home handle 等也走同一个 receiver 类，必须按 listener 类型过滤。
                Object listener = Reflect.fieldValue(param.thisObject, "val$listener");
                if (!isBackGestureListener(listener)) {
                    return;
                }
                MotionEvent event = (MotionEvent) input;
                if (!HookLogic.onEdgeTouchFromGestureMonitor(param.thisObject, event)) {
                    return;
                }
                if (InputSink.finish(param.thisObject, input, true)) {
                    param.setResult(null);
                    Xlog.debug("consumed input event (action=" + event.getActionMasked() + ")");
                } else {
                    HookLogic.abortStream(event);
                    Xlog.step("finishInputEvent UNAVAILABLE -> allow gesture");
                    // 这一条很少见（只在框架反射失败时出现），保留 step 便于排障
                    Logx.w("finishInputEvent 不可用，已放弃本次屏蔽（避免输入通道阻塞）");
                }
            } catch (Throwable t) {
                Logx.w("input hook callback failed: " + signature + " (放行原逻辑)", t);
            }
        }
    }

    /**
     * 是不是"返回手势"那条 gesture monitor 的 listener。
     *
     * 实测（LineageOS 24 / A17）：DisplayBackGestureHandlerImpl$inputEventReceiver$1；
     * AOSP main 的对应实现叫 EdgeBackGestureHandler（注册的 monitor 名是 "edge-swipe"，
     * 且**不设区域 = 整屏监视**，SystemUI 在 onMotionEvent 里自己按边缘条带过滤）。
     *
     * 两种命名都接受，其余（home handle 等复用同一包装类的通道）一律放行 ——
     * 这正是"不能无条件拦整个 receiver"的那条约束。
     */
    private static boolean isBackGestureListener(Object listener) {
        try {
            if (listener == null) {
                return false;
            }
            String name = listener.getClass().getName();
            return name.contains("DisplayBackGestureHandlerImpl")
                    || name.contains("EdgeBackGestureHandler")
                    || name.contains("BackGestureHandlerImpl")
                    || name.contains("BackGestureHandler");
        } catch (Throwable t) {
            return false;
        }
    }

    private static MotionEvent extractMotionEvent(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object arg : args) {
            if (arg instanceof MotionEvent) {
                return (MotionEvent) arg;
            }
        }
        return null;
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

    /**
     * 让旧架构方法"什么都不做并返回真值"。
     * 不调用原方法 -> 处理器状态机不启动 -> 手势不生效。
     */
    private static void consumeEvent(XC_MethodHook.MethodHookParam param) {
        try {
            Class<?> returnType = param.method instanceof Method
                    ? ((Method) param.method).getReturnType()
                    : void.class;
            if (returnType == void.class || returnType == Void.class) {
                param.setResult(null);
            } else if (returnType == boolean.class || returnType == Boolean.class) {
                param.setResult(Boolean.TRUE);
            } else if (returnType == int.class || returnType == Integer.class) {
                param.setResult(1);
            } else if (returnType == long.class || returnType == Long.class) {
                param.setResult(1L);
            } else if (returnType == float.class || returnType == Float.class) {
                param.setResult(1f);
            } else if (returnType == double.class || returnType == Double.class) {
                param.setResult(1d);
            } else {
                param.setResult(null);
            }
        } catch (Throwable t) {
            Logx.w("setResult failed", t);
        }
    }

}
