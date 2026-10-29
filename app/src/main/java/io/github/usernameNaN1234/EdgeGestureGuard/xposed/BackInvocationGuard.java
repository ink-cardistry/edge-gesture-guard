package io.github.usernameNaN1234.EdgeGestureGuard.xposed;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 第二道拦截：直接拦"执行返回"的那一刻。
 *
 * 实测 LineageOS 24 / Android 17 的真实 API：
 *   EdgeBackGestureHandler.mBackCallback : EdgeBackGestureHandler$5
 *   EdgeBackGestureHandler$5 (implements NavigationEdgeBackPlugin.BackCallback):
 *        cancelBack()
 *        setTriggerBack(boolean)
 *        setTriggerLongSwipe(boolean)
 *        triggerBack(boolean)      <-- 真正执行返回
 *
 * 旧版按 onBackInvoked() 找，所以 backCallback=0（挂不到）。
 * 这里改为挂 triggerBack，并用 this$0.mIsOnLeftEdge 判断左右。
 */
final class BackInvocationGuard {

    private static final Set<String> DONE = new HashSet<>();

    private BackInvocationGuard() {
    }

    static int install(Class<?> edgeClass) {
        if (edgeClass == null) {
            return 0;
        }
        Class<?> callbackClass = null;
        try {
            Field field = Reflect.field(edgeClass, "mBackCallback", "mCallback", "mEdgeBackCallback");
            if (field != null) {
                callbackClass = field.getType();
            }
        } catch (Throwable ignored) {
        }
        int count = 0;
        if (callbackClass != null && callbackClass != Object.class) {
            count += hook(callbackClass);
        }
        if (count == 0) {
            try {
                for (Class<?> inner : edgeClass.getDeclaredClasses()) {
                    count += hook(inner);
                }
            } catch (Throwable ignored) {
            }
        }
        return count;
    }

    private static int hook(final Class<?> cls) {
        Method[] methods;
        try {
            methods = cls.getDeclaredMethods();
        } catch (Throwable t) {
            return 0;
        }
        int count = 0;
        for (final Method method : methods) {
            final String name = method.getName();
            if (!"triggerBack".equals(name) && !"onBackInvoked".equals(name)) {
                continue;
            }
            String key = cls.getName() + "#" + name;
            if (DONE.contains(key)) {
                continue;
            }
            try {
                method.setAccessible(true);
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (!HookLogic.shouldBlockBackInvocation(param.thisObject)) {
                                return;
                            }
                            param.setResult(null);
                            Xlog.step("blocked " + name + " (" + cls.getSimpleName() + ")");
                        } catch (Throwable t) {
                            Xlog.step("back trigger hook err: " + t);
                        }
                    }
                });
                DONE.add(key);
                count++;
                Xlog.step("hooked [BACK-TRIGGER] " + cls.getSimpleName() + "#" + name);
            } catch (Throwable t) {
                Xlog.step("hook " + name + " failed: " + cls.getSimpleName() + " " + t);
            }
        }
        return count;
    }
}
