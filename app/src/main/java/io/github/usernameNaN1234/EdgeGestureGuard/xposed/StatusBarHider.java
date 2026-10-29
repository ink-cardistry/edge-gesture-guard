package io.github.usernameNaN1234.EdgeGestureGuard.xposed;

import android.view.View;

import io.github.usernameNaN1234.EdgeGestureGuard.common.Logx;
import io.github.usernameNaN1234.EdgeGestureGuard.common.ModuleConfig;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 游戏模式期间隐藏**顶部状态栏**（时钟/电量/图标那一条）。
 *
 * 为什么做这个：你反馈"滑动时顶部状态栏会一起跟出来"很碍事；1.6.2 里意外出现过
 * "状态栏不跟出来"的效果但那是误伤（它把通知行也藏了）。这里做成**明确、可控**的功能：
 * 只针对 PhoneStatusBarView 这一个视图，游戏模式开启时 INVISIBLE + alpha 0，退出时还原。
 *
 * 与通知栏下拉拦截的关系：两者互补 —— 触摸仍然被吞（不会展开），视觉上也不再有状态栏。
 */
final class StatusBarHider {

    private static final List<java.lang.ref.WeakReference<View>> BARS =
            Collections.synchronizedList(new ArrayList<java.lang.ref.WeakReference<View>>());
    /** shade 窗口根：只藏 PhoneStatusBarView 时，窗口背景/backdrop 还会留一层半透明，所以要连根一起压 */
    private static final List<java.lang.ref.WeakReference<View>> ROOTS =
            Collections.synchronizedList(new ArrayList<java.lang.ref.WeakReference<View>>());
    private static volatile boolean hooked;
    private static volatile boolean loggedHidden;

    private StatusBarHider() {
    }

    static int install(ClassLoader classLoader) {
        try {
            Class<?> cls = Reflect.load(classLoader,
                    "com.android.systemui.statusbar.phone.PhoneStatusBarView");
            if (cls == null) {
                Logx.w("StatusBarHider: PhoneStatusBarView 不存在，跳过");
                return 0;
            }
            Class<?> c = cls;
            int depth = 0;
            while (c != null && c != Object.class && depth++ < 4) {
                for (Method m : c.getDeclaredMethods()) {
                    if (!"onAttachedToWindow".equals(m.getName()) || m.getParameterTypes().length != 0) {
                        continue;
                    }
                    // 只挂 ROM 自己声明的实现，绝不碰 android.view.View
                    if (!Reflect.isRomDeclared(m.getDeclaringClass())) {
                        continue;
                    }
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
                    hooked = true;
                    Logx.i("hooked [STATUSBAR] " + Reflect.signature(m));
                    return 1;
                }
                c = c.getSuperclass();
            }
        } catch (Throwable t) {
            Logx.e("StatusBarHider.install failed", t);
        }
        return 0;
    }

    private static void remember(View view) {
        try {
            synchronized (BARS) {
                for (java.lang.ref.WeakReference<View> ref : BARS) {
                    if (ref != null && ref.get() == view) {
                        return;
                    }
                }
                BARS.add(new java.lang.ref.WeakReference<View>(view));
                try {
                    View root = view.getRootView();
                    if (root != null && root != view) {
                        boolean dup = false;
                        for (java.lang.ref.WeakReference<View> r : ROOTS) {
                            if (r != null && r.get() == root) {
                                dup = true;
                                break;
                            }
                        }
                        if (!dup) {
                            ROOTS.add(new java.lang.ref.WeakReference<View>(root));
                        }
                    }
                } catch (Throwable ignored) {
                }
                Xlog.step("STATUSBAR captured: " + view.getClass().getSimpleName());
            }
        } catch (Throwable ignored) {
        }
    }

    /** 游戏模式期间压住状态栏（幂等，可反复调用）。 */
    static void enforce() {
        ModuleConfig cfg = HookRuntime.config;
        if (!ArmController.isArmed() || cfg == null || !cfg.hideStatusBar) {
            return;
        }
        try {
            boolean changed = false;
            synchronized (BARS) {
                for (java.lang.ref.WeakReference<View> ref : BARS) {
                    View v = ref == null ? null : ref.get();
                    if (v != null && (v.getVisibility() != View.INVISIBLE || v.getAlpha() != 0f)) {
                        v.setVisibility(View.INVISIBLE);
                        v.setAlpha(0f);
                        changed = true;
                    }
                }
                // 连窗口根一起压掉：窗口背景/backdrop 那一层半透明就不会再露出来
                for (java.lang.ref.WeakReference<View> ref : ROOTS) {
                    View root = ref == null ? null : ref.get();
                    if (root != null && (root.getVisibility() != View.INVISIBLE || root.getAlpha() != 0f)) {
                        root.setVisibility(View.INVISIBLE);
                        root.setAlpha(0f);
                        changed = true;
                    }
                }
            }
            if (changed && !loggedHidden) {
                loggedHidden = true;
                Xlog.step("STATUSBAR hidden (game mode, incl. window root)");
            }
        } catch (Throwable t) {
            Logx.w("statusbar enforce failed", t);
        }
    }

    /** 退出游戏模式：还原。 */
    static void restore() {
        try {
            synchronized (BARS) {
                for (java.lang.ref.WeakReference<View> ref : BARS) {
                    View v = ref == null ? null : ref.get();
                    if (v != null) {
                        v.setAlpha(1f);
                        v.setVisibility(View.VISIBLE);
                    }
                }
                for (java.lang.ref.WeakReference<View> ref : ROOTS) {
                    View root = ref == null ? null : ref.get();
                    if (root != null) {
                        root.setAlpha(1f);
                        root.setVisibility(View.VISIBLE);
                    }
                }
            }
            loggedHidden = false;
        } catch (Throwable t) {
            Logx.w("statusbar restore failed", t);
        }
    }
}
