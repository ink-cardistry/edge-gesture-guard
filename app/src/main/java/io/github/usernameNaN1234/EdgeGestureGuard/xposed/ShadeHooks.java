package io.github.usernameNaN1234.EdgeGestureGuard.xposed;

import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import io.github.usernameNaN1234.EdgeGestureGuard.common.Logx;
import io.github.usernameNaN1234.EdgeGestureGuard.common.StatusReport;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 通知栏下拉屏蔽（功能二，全部在 com.android.systemui 进程内完成）。
 *
 * 拦截面 = shade 触摸链的每一层（谁存在挂谁）：
 *   NotificationShadeWindowView 的 dispatch/onIntercept/onTouchEvent
 *   -> NotificationShadeWindowViewController 的 handleDispatchTouchEvent /
 *      shouldInterceptTouchEvent / handleTouchEvent（InteractionEventHandler 实现）
 *   -> NotificationPanelViewController 的 onInterceptTouchEvent / onTouchEvent
 * 命中就把整条手势流吞掉（不喂下游），面板展开状态机不会启动。
 *
 * 1.5.2 新增的两个"实战补丁"（因为实测发现：通知栏能拦住，但状态栏区域仍能拉下来）：
 *  1) **动态根视图发现**：第一次收到触摸时读 getRootView()，把真实窗口根的类名打进日志，
 *     并把它的触摸方法也挂上 —— 不再假设窗口根一定叫 NotificationShadeWindowView；
 *  2) **SHADE-TOUCH 逐次日志**：每次 ACTION_DOWN 记录 类#方法 + rawY + 是否拦下（限流 500ms），
 *     用来回答"状态栏那一下到底有没有走到我们的 Hook"。
 *
 * 安全阀：前台命中 blockTopShade（或游戏模式已开）+ 起手 rawY 在顶部区 + 通知栏未展开。
 */
final class ShadeHooks {

    private static final Set<String> HOOKED = new HashSet<>();
    private static volatile boolean rootProbed;
    private static volatile long lastTouchLogAt;

    private ShadeHooks() {
    }

    static int install(ClassLoader classLoader, StatusReport status) {
        int total = 0;
        StringBuilder attached = new StringBuilder();
        StringBuilder missing = new StringBuilder();
        StringBuilder noMethod = new StringBuilder();
        try {
            List<Class<?>> classes = Targets.loadAll(classLoader, Targets.SHADE_TOUCH_CLASSES);
            for (Class<?> cls : classes) {
                int hooked = hookClass(cls);
                total += hooked;
                if (hooked > 0) {
                    if (attached.length() > 0) {
                        attached.append(" / ");
                    }
                    attached.append(cls.getSimpleName()).append('=').append(hooked);
                } else {
                    // 类存在但一个触摸方法都没挂上 —— 把这个类的方法名全打出来，
                    // 这是"补候选名"最直接的证据。
                    if (noMethod.length() > 0) {
                        noMethod.append(", ");
                    }
                    noMethod.append(cls.getSimpleName());
                    Logx.i("shade class found but 0 touch methods: " + cls.getName()
                            + " methods= " + Reflect.listMethods(cls));
                }
            }
            for (String name : Targets.SHADE_TOUCH_CLASSES) {
                boolean found = false;
                for (Class<?> cls : classes) {
                    if (cls.getName().equals(name)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    if (missing.length() > 0) {
                        missing.append(", ");
                    }
                    missing.append(Targets.simpleName(name));
                }
            }
        } catch (Throwable t) {
            Logx.e("ShadeHooks.install failed", t);
            if (status != null) {
                status.shadeState = StatusReport.SHADE_UNSUPPORTED;
                status.shadeTarget = "安装异常: " + t.getClass().getSimpleName();
            }
            return 0;
        }

        if (status != null) {
            status.shadeState = total > 0
                    ? StatusReport.SHADE_ATTACHED : StatusReport.SHADE_UNSUPPORTED;
            StringBuilder sb = new StringBuilder();
            sb.append("已挂载: ").append(attached.length() == 0 ? "无" : attached.toString());
            if (noMethod.length() > 0) {
                sb.append(" | 类在但无触摸方法: ").append(noMethod);
            }
            if (missing.length() > 0) {
                sb.append(" | 候选缺失: ").append(missing);
            }
            status.shadeTarget = sb.toString();
        }
        Logx.i("shade hooks: " + total + " 个入口; 已挂载="
                + (attached.length() == 0 ? "无" : attached.toString())
                + (noMethod.length() == 0 ? "" : "; 无触摸方法=" + noMethod)
                + (missing.length() == 0 ? "" : "; 候选缺失=" + missing));
        if (total == 0) {
            Logx.w("通知栏下拉 Hook 未挂载：该 ROM 的 shade 触摸链可能已被重构。"
                    + "logcat 里 'shade class found but 0 touch methods' 行会给出真实方法名。");
        }
        return total;
    }

    /** 挂一个类的触摸方法；install() 与"动态根视图发现"共用。 */
    static int hookClass(Class<?> cls) {
        int count = 0;
        List<Targets.TouchMethod> methods;
        try {
            methods = Targets.collectTouchMethods(cls, Targets.SHADE_TOUCH_METHODS);
        } catch (Throwable t) {
            Logx.w("collect shade methods failed: " + cls.getName(), t);
            return 0;
        }
        Logx.i("shade candidates on " + cls.getName() + ": " + methods.size());
        for (Targets.TouchMethod cand : methods) {
            String signature = cand.signature();
            String key = "SHADE|" + signature;
            if (HOOKED.contains(key)) {
                continue;
            }
            try {
                XposedBridge.hookMethod(cand.method, new ShadeTouchHook(signature, cand.carriesEvent));
                HOOKED.add(key);
                count++;
                Logx.i("hooked [SHADE] " + signature
                        + " returns " + cand.method.getReturnType().getSimpleName()
                        + (cand.carriesEvent ? " (带 MotionEvent)" : " (无参，用最近一次 DOWN)"));
            } catch (Throwable t) {
                Logx.w("hook failed: " + signature, t);
            }
        }
        return count;
    }

    /**
     * 第一次收到触摸时，找出真实的窗口根并补挂。
     *
     * 为什么需要：实测（LineageOS 24 / A17）里
     *   NotificationShadeWindowView=3 / PhoneStatusBarView=3 已挂上、通知栏能拦住，
     *   但**从状态栏（时钟那条）往下拉仍然能拉开** —— 说明那一下可能走的是另一个窗口根。
     * 这里不再假设类名：拿到实例就 getRootView()，把类名打出来并把它的触摸方法也挂上。
     */
    private static void probeRoot(Object owner) {
        if (rootProbed) {
            return;
        }
        rootProbed = true;
        try {
            if (!(owner instanceof View)) {
                Xlog.step("SHADE owner is not a View: "
                        + (owner == null ? "null" : owner.getClass().getName()));
                return;
            }
            View view = (View) owner;
            View root = view.getRootView();
            int[] loc = new int[2];
            try {
                view.getLocationOnScreen(loc);
            } catch (Throwable ignored) {
            }
            Xlog.step("SHADE touched class=" + view.getClass().getName()
                    + " | windowRoot=" + (root == null ? "?" : root.getClass().getName())
                    + " | viewAt=" + loc[0] + "," + loc[1]
                    + " | viewSize=" + view.getWidth() + "x" + view.getHeight());
            if (root != null) {
                int added = hookClass(root.getClass());
                if (added > 0) {
                    Xlog.step("SHADE dynamic hook on windowRoot: +" + added + " ("
                            + root.getClass().getSimpleName() + ")");
                }
            }
        } catch (Throwable t) {
            Logx.w("probeRoot failed", t);
        }
    }

    /** 每次 ACTION_DOWN 记一行（限流 500ms）：用来判断"这一下到底有没有走到 Hook"。 */
    private static void logTouch(String signature, MotionEvent event, boolean blocked) {
        long now = SystemClock.uptimeMillis();
        if (now - lastTouchLogAt < 500L) {
            return;
        }
        lastTouchLogAt = now;
        try {
            Xlog.step("SHADE-TOUCH " + signature
                    + " rawY=" + (int) event.getRawY()
                    + " y=" + (int) event.getY()
                    + " blocked=" + blocked);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // 回调
    // ------------------------------------------------------------------

    private static final class ShadeTouchHook extends XC_MethodHook {

        private final String signature;
        private final boolean carriesEvent;

        ShadeTouchHook(String signature, boolean carriesEvent) {
            this.signature = signature;
            this.carriesEvent = carriesEvent;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                boolean consume;
                MotionEvent event = null;
                if (carriesEvent) {
                    event = extract(param.args);
                    if (event == null) {
                        return;
                    }
                    probeRoot(param.thisObject);
                    consume = HookLogic.gateShadeTouch(param.thisObject, event,
                            ShadeStateProbe.isExpanded(param.thisObject));
                } else {
                    // 无参 onQsIntercept()：靠"最近一次 DOWN 在顶部区 + 规则命中"判断，
                    // 再叠加一次"通知栏未展开"的安全阀。
                    consume = HookLogic.shouldBlockCurrentDown()
                            && !ShadeStateProbe.isExpanded(param.thisObject);
                }
                // 只在两个场景打日志：验证模式，或"顶部区却没拦住"（这才是要查的）
                if (event != null && event.getActionMasked() == MotionEvent.ACTION_DOWN
                        && (HookRuntime.verifyMode() || !consume)) {
                    logTouch(signature, event, consume);
                }
                if (consume) {
                    InputSink.consumeReturn(param);
                }
            } catch (Throwable t) {
                // 出异常就不 setResult —— 原方法照常执行 = 恢复系统默认行为
                Logx.w("shade hook callback failed: " + signature + " (放行原逻辑)", t);
            }
        }

        private MotionEvent extract(Object[] args) {
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
    }

    /**
     * "通知栏是否已展开"探测（只读、失败即 false）。
     * 取不到时不是灾难：拦截还要求"前台命中 + 起手在顶部 12%"两个条件。
     */
    private static final class ShadeStateProbe {

        private static final String[] METHODS = {
                "isFullyExpanded", "isExpanded", "isPanelExpanded",
                "isShadeExpanded", "isFullyCollapsed", "getExpansionFraction",
                "getShadeExpansionFrac", "getExpandedFraction",
        };
        private static final String[] BOOL_FIELDS = {"mIsExpanded", "mExpanded", "mPanelExpanded"};
        private static final String[] FLOAT_FIELDS = {"mExpandedFraction", "mExpansionFraction"};

        private static volatile Class<?> probedClass;
        private static volatile Method expandMethod;
        private static volatile Field boolField;
        private static volatile Field floatField;
        private static volatile boolean probed;
        private static volatile boolean warned;

        static boolean isExpanded(Object owner) {
            if (owner == null) {
                return false;
            }
            try {
                Class<?> cls = owner.getClass();
                if (cls != probedClass) {
                    probe(cls);
                }
                Method m = expandMethod;
                if (m != null) {
                    Object value = m.invoke(owner);
                    if (value instanceof Boolean) {
                        if ("isFullyCollapsed".equals(m.getName())) {
                            return !((Boolean) value);
                        }
                        return (Boolean) value;
                    }
                    if (value instanceof Number) {
                        return ((Number) value).floatValue() > 0.99f;
                    }
                    return false;
                }
                Field bf = boolField;
                if (bf != null) {
                    Object value = bf.get(owner);
                    return value instanceof Boolean && (Boolean) value;
                }
                Field ff = floatField;
                if (ff != null) {
                    Object value = ff.get(owner);
                    return value instanceof Number && ((Number) value).floatValue() > 0.99f;
                }
                return false;
            } catch (Throwable t) {
                return false;
            }
        }

        private static void probe(Class<?> cls) {
            probedClass = cls;
            expandMethod = null;
            boolField = null;
            floatField = null;
            try {
                Class<?> c = cls;
                int guard = 0;
                while (c != null && c != Object.class && guard++ < 6) {
                    for (Method m : c.getDeclaredMethods()) {
                        if (m.getParameterTypes().length != 0) {
                            continue;
                        }
                        String name = m.getName();
                        for (String candidate : METHODS) {
                            if (candidate.equals(name)) {
                                m.setAccessible(true);
                                expandMethod = m;
                                break;
                            }
                        }
                        if (expandMethod != null) {
                            break;
                        }
                    }
                    if (expandMethod != null) {
                        break;
                    }
                    c = c.getSuperclass();
                }
            } catch (Throwable ignored) {
            }
            if (expandMethod == null) {
                boolField = Reflect.field(cls, BOOL_FIELDS);
                floatField = Reflect.field(cls, FLOAT_FIELDS);
            }
            if (!probed) {
                probed = true;
                Logx.i("shade expansion probe on " + cls.getSimpleName() + ": method="
                        + (expandMethod == null ? "none" : expandMethod.getName())
                        + ", boolField=" + (boolField == null ? "none" : boolField.getName())
                        + ", floatField=" + (floatField == null ? "none" : floatField.getName()));
            }
            if (expandMethod == null && boolField == null && floatField == null && !warned) {
                warned = true;
                Logx.w("无法读取通知栏展开状态：若出现『通知栏展开时顶部点击被吞』，"
                        + "请把 shade 类的字段名贴出来补候选。");
            }
        }
    }
}
