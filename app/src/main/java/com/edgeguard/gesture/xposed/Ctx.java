package com.edgeguard.gesture.xposed;

import android.content.Context;

import com.edgeguard.gesture.common.Logx;
import com.edgeguard.gesture.common.ModuleContract;

import java.lang.reflect.Method;

/**
 * 获取 Context 的工具（**不 import 任何 LSPosed 专有类**）。
 *
 * 为什么重要：之前 XposedEntry 直接写了 AndroidAppHelper.currentApplication()。
 * 如果目标 LSPosed 版本没有这个类（或类加载阶段解析失败），整条 IPC 初始化会断，
 * 表现就是"Hook 装上了但状态永远回不去"。
 * 这里改成纯反射 + 多级兜底：AndroidAppHelper -> ActivityThread.currentApplication -> SystemContext。
 */
final class Ctx {

    private Ctx() {
    }

    static Context appContext() {
        try {
            Class<?> helper = Class.forName("android.app.AndroidAppHelper");
            Method method = helper.getMethod("currentApplication");
            Object app = method.invoke(null);
            if (app instanceof Context) {
                return (Context) app;
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object app = activityThread.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) {
                return (Context) app;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * SystemUI 自己的 Context（包名 com.android.systemui，uid 与调用方一致）。
     *
     * 关键：Settings.Global 的写入会校验 "调用包名 vs 调用 uid"。
     * 用 SystemContext（包名 android）去写会抛
     *   SecurityException: Given calling package android does not match caller's uid 10111
     * 所以必须优先用 SystemUI 这个 context。
     */
    static Context systemUiContext() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = activityThread.getMethod("currentActivityThread").invoke(null);
            if (thread == null) {
                return null;
            }
            Method method = null;
            Class<?> cls = thread.getClass();
            while (cls != null && method == null) {
                try {
                    method = cls.getDeclaredMethod("getSystemUiContext");
                } catch (Throwable ignored) {
                    cls = cls.getSuperclass();
                }
            }
            if (method == null) {
                return null;
            }
            method.setAccessible(true);
            Object context = method.invoke(thread);
            if (context instanceof Context) {
                return (Context) context;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** SystemContext：进程启动极早期就能拿到（注意包名是 android，写 Settings 会被拒）。 */
    static Context systemContext() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = activityThread.getMethod("currentActivityThread").invoke(null);
            if (thread == null) {
                return null;
            }
            Object context = thread.getClass().getMethod("getSystemContext").invoke(thread);
            if (context instanceof Context) {
                return (Context) context;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 专门用于写 Settings.Global 的 context。
     *
     * 实测（LineageOS 24 / Android 17）：
     *   - handleLoadPackage 阶段 appContext 是 null；
     *   - getSystemUiContext() 反射拿不到（隐藏 API）；
     *   - SystemContext 的包名是 "android"，写 Settings 直接被拒：
     *     SecurityException: Given calling package android does not match caller's uid 10111
     *
     * 解法：拿 SystemContext 再 createPackageContext("com.android.systemui")，
     * 得到包名 = com.android.systemui、uid = 10111 的 context，包名/uid 校验才能过。
     */
    static Context settingsContext() {
        Context context = appContext();
        if (context != null) {
            return context;
        }
        context = systemUiContext();
        if (context != null) {
            return context;
        }
        try {
            Context system = systemContext();
            if (system != null) {
                return system.createPackageContext(ModuleContract.SYSTEMUI_PACKAGE,
                        Context.CONTEXT_IGNORE_SECURITY | Context.CONTEXT_INCLUDE_CODE);
            }
        } catch (Throwable ignored) {
        }
        return systemContext();
    }

    static Context any() {
        return settingsContext();
    }
}
