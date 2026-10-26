package com.edgeguard.gesture.xposed;

import android.view.View;

import com.edgeguard.gesture.common.Logx;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 游戏模式期间隐藏导航栏（手势胶囊 / 三键条）的"临时滑出"。
 *
 * 背景：底部上滑手势由 Launcher 执行（策略 B 已拦）；但"从底边滑一下让导航栏露出来"
 * 是 system_server 的 DisplayPolicy 发起的 transient 请求，最终由 SystemUI 的
 * NavigationBarTransitions 把视图切到 TRANSIENT —— 与 Launcher 无关，所以还要单独处理。
 *
 * 1.6.0 的修正（上一版挂载数=0 的原因）：
 *   NavigationBarTransitions **自己没有** transitionTo(int)，它继承自
 *   com.android.systemui.statusbar.phone.BarTransitions；只扫本类声明方法必然为 0。
 *   现在会沿父类链找 transitionTo / applyMode（1~3 个参数，第一个是 int），
 *   并且**按实例类名过滤**（只有 NavigationBar 的实例才处理，不影响状态栏的同一套基类）。
 */
final class NavBarHooks {

    private static final String[] TRANSITION_CLASSES = {
            "com.android.systemui.navigationbar.NavigationBarTransitions",
            "com.android.systemui.navigationbar.views.NavigationBarTransitions",
            // 这两个是"父类兜底"：transitionTo/applyMode 真正声明在这里
            "com.android.systemui.statusbar.phone.BarTransitions",
            "com.android.systemui.navigationbar.NavigationBarView",
            "com.android.systemui.navigationbar.views.NavigationBarView",
    };

    /**
     * 模式切换的方法名候选。
     *
     * 实测（LineageOS 24 / A17，NavigationBarTransitions 只有这些方法）：
     *   applyDarkIntensity/1  applyLightsOut/2  getTintAnimationDuration/0
     *   onTransition/3        setAutoDim/1      applyModeBackground/2   modeToString$1/1
     * 也就是说这个 ROM 里 transitionTo/applyMode 都在父类且不在候选类里，
     * 真正的切换回调是 **onTransition(int oldMode, int newMode, boolean animate)**。
     */
    private static final String[] METHOD_NAMES = {
            "onTransition",
            "transitionTo",
            "applyMode",
            "applyModeBackground",
            "applyLightsOut",
            "updateMode",
    };

    private static final Set<String> HOOKED = new HashSet<>();
    private static volatile long lastLogAt;
    /** 已捕获的导航栏视图（onAttachedToWindow 时记录），用于"保险式"强制隐藏 */
    private static final java.util.List<java.lang.ref.WeakReference<View>> BARS =
            java.util.Collections.synchronizedList(new java.util.ArrayList<java.lang.ref.WeakReference<View>>());
    /** 被我们藏起来的导航栏视图，退出游戏模式时用于还原 */
    private static volatile View lastHidden;
    private static volatile boolean methodDumpDone;

    private NavBarHooks() {
    }

    static int install(ClassLoader classLoader) {
        int total = 0;
        try {
            List<Class<?>> classes = Targets.loadAll(classLoader, TRANSITION_CLASSES);
            for (Class<?> cls : classes) {
                total += hookClass(cls);
                total += hookAttach(cls);
            }
            if (total == 0 && !methodDumpDone) {
                methodDumpDone = true;
                for (Class<?> cls : classes) {
                    Logx.w("导航栏类无可用方法: " + cls.getName()
                            + " methods= " + Reflect.listMethods(cls));
                }
            }
        } catch (Throwable t) {
            Logx.e("NavBarHooks.install failed", t);
        }
        if (total == 0) {
            Logx.w("导航栏 Hook 未挂载（方法名可能不同）：游戏模式期间导航栏仍可能滑出");
        } else {
            Logx.i("导航栏 Hook 已挂载: " + total + " 个入口");
        }
        return total;
    }

    /** 沿父类链收集 transitionTo / applyMode 这类"模式切换"方法并挂载。 */
    private static int hookClass(Class<?> cls) {
        int count = 0;
        Set<Method> methods = new LinkedHashSet<>();
        Class<?> c = cls;
        int depth = 0;
        while (c != null && c != Object.class && depth++ < 5) {
            Method[] declared;
            try {
                declared = c.getDeclaredMethods();
            } catch (Throwable t) {
                break;
            }
            for (Method m : declared) {
                if (Modifier.isStatic(m.getModifiers()) || Modifier.isAbstract(m.getModifiers())) {
                    continue;
                }
                if (!isWanted(m)) {
                    continue;
                }
                methods.add(m);
            }
            c = c.getSuperclass();
        }
        for (Method m : methods) {
            String signature = Reflect.signature(m);
            String key = "NAV|" + signature;
            if (HOOKED.contains(key)) {
                continue;
            }
            try {
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new ModeHook(signature));
                HOOKED.add(key);
                count++;
                Logx.i("hooked [NAV] " + signature);
            } catch (Throwable t) {
                Logx.w("hook failed: " + signature, t);
            }
        }
        if (count == 0 && !methodDumpDone) {
            Logx.i("导航栏候选无匹配方法: " + cls.getName());
        }
        return count;
    }

    /**
     * 捕获导航栏视图实例：挂 NavigationBarView#onAttachedToWindow()（实测存在）。
     * 实例被记下来后，enforce() 才能在游戏模式期间按需强制隐藏。
     */
    private static int hookAttach(Class<?> cls) {
        int count = 0;
        Class<?> c = cls;
        int depth = 0;
        while (c != null && c != Object.class && depth++ < 4) {
            for (Method m : c.getDeclaredMethods()) {
                if (!"onAttachedToWindow".equals(m.getName())
                        || m.getParameterTypes().length != 0) {
                    continue;
                }
                String key = "NAV-ATTACH|" + Reflect.signature(m);
                if (HOOKED.contains(key)) {
                    continue;
                }
                try {
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (param.thisObject instanceof View) {
                                    remember((View) param.thisObject);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                    HOOKED.add(key);
                    count++;
                    Logx.i("hooked [NAV-ATTACH] " + Reflect.signature(m));
                } catch (Throwable t) {
                    Logx.w("hook attach failed: " + Reflect.signature(m), t);
                }
                break;
            }
            c = c.getSuperclass();
        }
        return count;
    }

    /** 名字命中 + 第一个参数是 int（transitionTo(int[, boolean]) / applyMode(int[…])）。 */
    private static boolean isWanted(Method m) {
        boolean nameOk = false;
        for (String n : METHOD_NAMES) {
            if (n.equals(m.getName())) {
                nameOk = true;
                break;
            }
        }
        if (!nameOk) {
            return false;
        }
        Class<?>[] params = m.getParameterTypes();
        return params.length >= 1 && params.length <= 3 && params[0] == int.class;
    }

    private static final class ModeHook extends XC_MethodHook {

        private final String signature;

        ModeHook(String signature) {
            this.signature = signature;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (!ArmController.isArmed()) {
                    return;
                }
                // 只处理导航栏的实例：同一个 applyMode 基类也被状态栏复用，
                // 不加这条过滤会把状态栏也藏起来。
                Object self = param.thisObject;
                String cls = self == null ? "" : self.getClass().getName();
                if (!cls.contains("NavigationBar")) {
                    return;
                }
                boolean hidden = hideBarView(self);
                param.setResult(null);
                long now = android.os.SystemClock.elapsedRealtime();
                if (now - lastLogAt > 1000L) {
                    lastLogAt = now;
                    Xlog.step("NAV suppressed " + signature + " armed=true viewHidden=" + hidden);
                }
            } catch (Throwable t) {
                Logx.w("nav hook callback failed: " + signature + " (放行原逻辑)", t);
            }
        }
    }

    /**
     * 保险式强制隐藏：游戏模式期间每 3 秒由 ArmController 的看门狗调一次。
     *
     * 为什么需要：onTransition 是"模式切换"的入口，但 ROM 可能还有别的路径
     * 让导航栏重新可见（例如直接改窗口 alpha）。这里把捕获到的 NavigationBarView
     * 再按一遍，成本只是一次 visibility 赋值。
     */
    static void enforce() {
        if (!ArmController.isArmed()) {
            return;
        }
        try {
            synchronized (BARS) {
                for (java.lang.ref.WeakReference<View> ref : BARS) {
                    View v = ref == null ? null : ref.get();
                    if (v == null) {
                        continue;
                    }
                    if (v.getVisibility() != View.INVISIBLE || v.getAlpha() != 0f) {
                        v.setVisibility(View.INVISIBLE);
                        v.setAlpha(0f);
                        lastHidden = v;
                        Xlog.step("NAV enforce hide (view=" + v.getClass().getSimpleName() + ")");
                    }
                }
            }
        } catch (Throwable t) {
            Logx.w("nav enforce failed", t);
        }
    }

    /** 记录导航栏视图实例（由 onAttachedToWindow Hook 调用）。 */
    private static void remember(View view) {
        if (view == null) {
            return;
        }
        try {
            synchronized (BARS) {
                for (java.lang.ref.WeakReference<View> ref : BARS) {
                    if (ref != null && ref.get() == view) {
                        return;
                    }
                }
                BARS.add(new java.lang.ref.WeakReference<View>(view));
                Xlog.step("NAV captured bar view: " + view.getClass().getName());
            }
        } catch (Throwable ignored) {
        }
    }

    /** 退出游戏模式时还原导航栏。 */
    static void restore() {
        try {
            View view = lastHidden;
            if (view != null) {
                view.setAlpha(1f);
                view.setVisibility(View.VISIBLE);
                Xlog.step("NAV restored after game mode off");
            }
        } catch (Throwable t) {
            Logx.w("nav restore failed", t);
        }
    }

    /** 从 transitions 对象里找出导航栏 View 并强制隐藏。找不到就返回 false（不影响主逻辑）。 */
    private static boolean hideBarView(Object target) {
        if (target == null) {
            return false;
        }
        try {
            Class<?> cls = target.getClass();
            int depth = 0;
            while (cls != null && cls != Object.class && depth++ < 5) {
                for (Field f : cls.getDeclaredFields()) {
                    if (!View.class.isAssignableFrom(f.getType())) {
                        continue;
                    }
                    try {
                        f.setAccessible(true);
                        Object v = f.get(target);
                        if (v instanceof View) {
                            View view = (View) v;
                            view.setVisibility(View.INVISIBLE);
                            view.setAlpha(0f);
                            lastHidden = view;
                            return true;
                        }
                    } catch (Throwable ignored) {
                    }
                }
                cls = cls.getSuperclass();
            }
        } catch (Throwable t) {
            Logx.w("hideBarView failed", t);
        }
        return false;
    }
}
