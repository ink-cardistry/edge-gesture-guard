package com.edgeguard.gesture.xposed;

import android.view.View;

import com.edgeguard.gesture.common.Logx;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 游戏模式期间隐藏导航栏（手势胶囊 / 三键条）的"临时滑出"。
 *
 * 为什么还需要这一层：
 *   底部上滑手势的执行方是 Launcher（策略 B 已拦截）；但"从底边滑一下把导航栏临时露出来"
 *   是 **system_server 的 DisplayPolicy** 发起的 transient 请求，最终由 SystemUI 的
 *   NavigationBarTransitions 把导航栏视图切到 TRANSIENT 模式 —— 与 Launcher 完全无关。
 *   所以手势拦住后，导航栏仍然会冒出来。
 *
 * 做法（全部只影响本机视觉，不改系统状态）：
 *   1) Hook NavigationBarTransitions#transitionTo(int)：游戏模式开启时**跳过这次切换**；
 *   2) 顺手把导航栏视图 setVisibility(INVISIBLE) + alpha=0，确保它立刻消失；
 *   3) 退出游戏模式后不再拦截，下一次正常的 transitionTo 会把它恢复。
 *
 * 安全：任何一步失败都只记日志；拿不到 View 就只跳过 transitionTo，不会崩。
 */
final class NavBarHooks {

    /** 候选类：不同 ROM 的位置（navigationbar / navigationbar.views）。 */
    private static final String[] TRANSITION_CLASSES = {
            "com.android.systemui.navigationbar.NavigationBarTransitions",
            "com.android.systemui.navigationbar.views.NavigationBarTransitions",
            "com.android.systemui.navigationbar.NavigationBarView",
            "com.android.systemui.navigationbar.views.NavigationBarView",
    };

    private static final Set<String> HOOKED = new HashSet<>();
    private static volatile long lastLogAt;
    /** 被我们藏起来的导航栏视图，退出游戏模式时用于还原 */
    private static volatile View lastHidden;

    private NavBarHooks() {
    }

    static int install(ClassLoader classLoader) {
        int total = 0;
        try {
            List<Class<?>> classes = Targets.loadAll(classLoader, TRANSITION_CLASSES);
            for (Class<?> cls : classes) {
                total += hookTransition(cls);
            }
        } catch (Throwable t) {
            Logx.e("NavBarHooks.install failed", t);
        }
        if (total == 0) {
            Logx.w("导航栏 Hook 未挂载（类名可能不同）：游戏模式期间导航栏仍可能滑出");
        } else {
            Logx.i("导航栏 Hook 已挂载: " + total + " 个入口");
        }
        return total;
    }

    private static int hookTransition(Class<?> cls) {
        int count = 0;
        Method[] methods;
        try {
            methods = cls.getDeclaredMethods();
        } catch (Throwable t) {
            return 0;
        }
        for (Method m : methods) {
            if (!"transitionTo".equals(m.getName())) {
                continue;
            }
            Class<?>[] params = m.getParameterTypes();
            if (params.length != 1 || params[0] != int.class) {
                continue;
            }
            String signature = Reflect.signature(m);
            String key = "NAV|" + signature;
            if (HOOKED.contains(key)) {
                continue;
            }
            try {
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new TransitionHook(signature));
                HOOKED.add(key);
                count++;
                Logx.i("hooked [NAV] " + signature);
            } catch (Throwable t) {
                Logx.w("hook failed: " + signature, t);
            }
        }
        return count;
    }

    private static final class TransitionHook extends XC_MethodHook {

        private final String signature;

        TransitionHook(String signature) {
            this.signature = signature;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (!ArmController.isArmed()) {
                    return;
                }
                int mode = (Integer) param.args[0];
                boolean hidden = hideBarView(param.thisObject);
                // 跳过这次切换：导航栏保持"不显示"，直到游戏模式关闭
                param.setResult(null);
                long now = android.os.SystemClock.elapsedRealtime();
                if (now - lastLogAt > 1000L) {
                    lastLogAt = now;
                    Xlog.step("NAV suppressed transitionTo(mode=" + mode + ") armed=true viewHidden="
                            + hidden + " via " + signature);
                }
            } catch (Throwable t) {
                Logx.w("nav hook callback failed: " + signature + " (放行原逻辑)", t);
            }
        }
    }

    /** 退出游戏模式时还原导航栏（下一次正常的 transitionTo 也会恢复，这里做即时兜底）。 */
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
            while (cls != null && cls != Object.class && depth++ < 4) {
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
