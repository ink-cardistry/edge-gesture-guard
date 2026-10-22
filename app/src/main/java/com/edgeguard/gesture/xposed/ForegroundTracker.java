package com.edgeguard.gesture.xposed;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.SystemClock;

import com.edgeguard.gesture.common.Logx;

import java.lang.reflect.Field;
import java.util.List;

/**
 * 前台应用检测（运行在 SystemUI 进程里）。
 *
 * 三档降级（与需求一致）：
 *  1) ActivityTaskManager 反射查询 —— 首选，能同时拿到包名和 Activity；
 *     但注意：是否能调用成功取决于 SystemUI 自身持有的权限，
 *     失败（SecurityException / 隐藏 API 限制）时只记一次日志并进入冷却，不反复抛异常；
 *  2) 无障碍助手推送 —— 模块 App 里可选开启 AccessibilityService，
 *     把 TYPE_WINDOW_STATE_CHANGED 的包名/类名广播过来；这是"一定能拿到"的通道；
 *  3) UsageStatsManager.queryEvents —— 最后兜底，只拿得到包名。
 *
 * 三档全失败 -> 返回 null -> 调用方默认"不屏蔽"（安全默认）。
 * 查询节流：400ms 缓存 + 失败冷却，避免每次触摸都走 Binder。
 */
final class ForegroundTracker {

    static final class Fg {
        final String packageName;
        final String activityName;
        final String strategy;

        Fg(String packageName, String activityName, String strategy) {
            this.packageName = packageName;
            this.activityName = activityName;
            this.strategy = strategy;
        }

        String describe() {
            if (packageName == null) {
                return "unknown";
            }
            return packageName
                    + (activityName == null || activityName.length() == 0 ? "" : "/" + activityName)
                    + " [" + strategy + "]";
        }
    }

    private static final Object LOCK = new Object();

    /**
     * 结果缓存时间。
     *
     * 400ms 太短：一次滑动往往超过 400ms，会在滑动途中反复触发 Binder 查询。
     * 前台应用切换的频率远低于此，3s 足够准确，同时把 Binder 调用量降到原来的 1/7。
     */
    private static final long CACHE_TTL_MS = 3000L;
    /** 无障碍推送的有效期：这类事件只在切换窗口时来，给足余量。 */
    private static final long PUSH_TTL_MS = 10L * 60L * 1000L;

    private static volatile Fg cached;
    private static volatile long cachedAt;
    private static volatile String pushedPackage;
    private static volatile String pushedActivity;
    private static volatile long pushedAt;
    private static volatile long atmsRetryAt;
    private static volatile long usageRetryAt;
    private static volatile String lastError = "";

    private ForegroundTracker() {
    }

    static String lastError() {
        return lastError;
    }

    /** 无障碍助手 / App 推来的前台变化。 */
    static void onPushed(String packageName, String activityName) {
        if (packageName == null || packageName.length() == 0) {
            return;
        }
        pushedPackage = packageName;
        pushedActivity = activityName == null ? "" : activityName;
        pushedAt = SystemClock.elapsedRealtime();
        cached = new Fg(packageName, pushedActivity, "accessibility-push");
        cachedAt = pushedAt;
        Logx.i("foreground pushed: " + cached.describe());
    }

    // ------------------------------------------------------------------
    // 推送式前台变化（TaskStackListener）
    // ------------------------------------------------------------------

    private static volatile boolean tracking;
    private static volatile android.os.Handler worker;
    private static volatile long lastStackEventAt;

    /** TaskStackListener 注册成功后置位：之后触摸回调不再做 Binder 查询。 */
    static void enableTracking() {
        tracking = true;
    }

    static boolean isTracking() {
        return tracking;
    }

    /** 仅供触摸回调使用：直接拿缓存，绝不触发查询（缓存过期也先用旧值）。 */
    static Fg cachedOnly() {
        return cached;
    }

    /** 系统回调（Binder 线程）：只置脏 + 异步刷新，去抖 250ms。 */
    static void onTaskStackChanged() {
        try {
            long now = SystemClock.uptimeMillis();
            if (now - lastStackEventAt < 250L) {
                return;
            }
            lastStackEventAt = now;
            android.os.Handler h = worker();
            if (h != null) {
                h.removeCallbacks(REFRESH);
                h.postDelayed(REFRESH, 120L);
            }
        } catch (Throwable ignored) {
        }
    }

    private static final Runnable REFRESH = new Runnable() {
        @Override
        public void run() {
            try {
                forceRefresh();
            } catch (Throwable t) {
                Logx.w("foreground refresh failed", t);
            }
        }
    };

    private static void forceRefresh() {
        synchronized (LOCK) {
            Fg fg = queryAtms();
            if (fg == null) {
                fg = fromPush(SystemClock.elapsedRealtime());
            }
            if (fg == null) {
                fg = queryUsageStats();
            }
            if (fg != null) {
                cached = fg;
                cachedAt = SystemClock.elapsedRealtime();
                HookRuntime.noteForeground(fg.strategy, "");
            }
        }
    }

    private static android.os.Handler worker() {
        android.os.Handler h = worker;
        if (h != null) {
            return h;
        }
        synchronized (ForegroundTracker.class) {
            h = worker;
            if (h == null) {
                android.os.HandlerThread thread = new android.os.HandlerThread("edgeguard-fg");
                thread.start();
                h = new android.os.Handler(thread.getLooper());
                worker = h;
            }
        }
        return h;
    }

    /** 立即在后台刷新一次（只在初始化 / 注册监听后调用一次，不是定时器）。 */
    static void refreshAsync() {
        try {
            android.os.Handler h = worker();
            if (h != null) {
                h.post(REFRESH);
            }
        } catch (Throwable t) {
            Logx.w("refreshAsync failed", t);
        }
    }

    static Fg get() {
        long now = SystemClock.elapsedRealtime();
        Fg c = cached;
        if (c != null && now - cachedAt <= CACHE_TTL_MS) {
            return c;
        }
        synchronized (LOCK) {
            now = SystemClock.elapsedRealtime();
            c = cached;
            if (c != null && now - cachedAt <= CACHE_TTL_MS) {
                return c;
            }
            Fg fg = queryAtms();
            if (fg == null) {
                fg = fromPush(now);
            }
            if (fg == null) {
                fg = queryUsageStats();
            }
            if (fg != null) {
                cached = fg;
                cachedAt = now;
                HookRuntime.noteForeground(fg.strategy, "");
            } else {
                HookRuntime.noteForeground("none", lastError.length() == 0
                        ? "无法获取前台应用（默认不屏蔽）" : lastError);
            }
            return fg;
        }
    }

    private static Fg fromPush(long now) {
        String pkg = pushedPackage;
        if (pkg == null) {
            return null;
        }
        if (now - pushedAt > PUSH_TTL_MS) {
            Logx.i("pushed foreground expired: " + pkg);
            return null;
        }
        return new Fg(pkg, pushedActivity, "accessibility-push");
    }

    // ------------------------------------------------------------------
    // 档位 1：ActivityTaskManager（反射）
    // ------------------------------------------------------------------

    private static Fg queryAtms() {
        long now = SystemClock.elapsedRealtime();
        if (now < atmsRetryAt) {
            return null;
        }
        try {
            Class<?> atmClass = Class.forName("android.app.ActivityTaskManager");
            Object atm = atmClass.getMethod("getService").invoke(null);
            if (atm == null) {
                throw new IllegalStateException("ActivityTaskManager.getService() == null");
            }

            // 形态 A：getFocusedRootTaskInfo()
            try {
                Object info = Reflect.call(atm, "getFocusedRootTaskInfo");
                Fg fg = fromTaskInfo(info, "atm:focused-task");
                if (fg != null) {
                    return fg;
                }
            } catch (Throwable t) {
                Logx.i("atm: getFocusedRootTaskInfo 不可用: " + t);
            }

            // 形态 B：getTasks(int, boolean, boolean, int)
            try {
                Object list = Reflect.call(atm, "getTasks", 1, Boolean.FALSE, Boolean.FALSE, 0);
                Fg fg = fromTaskList(list, "atm:getTasks4");
                if (fg != null) {
                    return fg;
                }
            } catch (Throwable t) {
                Logx.i("atm: getTasks(4 args) 不可用: " + t);
            }

            // 形态 C：getTasks(int)
            try {
                Object list = Reflect.call(atm, "getTasks", 1);
                Fg fg = fromTaskList(list, "atm:getTasks1");
                if (fg != null) {
                    return fg;
                }
            } catch (Throwable t) {
                Logx.i("atm: getTasks(1 arg) 不可用: " + t);
            }

            // 形态 D：旧版 ActivityManager.getService().getTasks(int, int)
            try {
                Class<?> amClass = Class.forName("android.app.ActivityManager");
                Object am = amClass.getMethod("getService").invoke(null);
                Object list = Reflect.call(am, "getTasks", 1, 0);
                Fg fg = fromTaskList(list, "am:getTasks2");
                if (fg != null) {
                    return fg;
                }
            } catch (Throwable t) {
                Logx.i("am: getTasks(2 args) 不可用: " + t);
            }

            lastError = "ActivityTaskManager 可调用但未返回 topActivity";
            atmsRetryAt = now + 10000L;
            return null;
        } catch (Throwable t) {
            lastError = "ActivityTaskManager: " + t.getClass().getSimpleName() + ": " + t.getMessage();
            atmsRetryAt = now + 30000L;
            Logx.w("ATMS 前台查询失败（将降级到推送/UsageStats）", t);
            return null;
        }
    }

    private static Fg fromTaskList(Object list, String strategy) {
        if (!(list instanceof List)) {
            return null;
        }
        for (Object o : (List<?>) list) {
            Fg fg = fromTaskInfo(o, strategy);
            if (fg != null) {
                return fg;
            }
        }
        return null;
    }

    private static Fg fromTaskInfo(Object info, String strategy) {
        if (info == null) {
            return null;
        }
        ComponentName component = componentField(info, "topActivity");
        if (component == null) {
            component = componentField(info, "baseActivity");
        }
        if (component == null) {
            component = componentField(info, "origActivity");
        }
        if (component == null) {
            return null;
        }
        String pkg = component.getPackageName();
        if (pkg == null || pkg.length() == 0) {
            return null;
        }
        return new Fg(pkg, component.getClassName(), strategy);
    }

    private static ComponentName componentField(Object target, String name) {
        try {
            Field f = Reflect.field(target.getClass(), name);
            if (f == null) {
                return null;
            }
            Object v = f.get(target);
            return v instanceof ComponentName ? (ComponentName) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 档位 3：UsageStatsManager
    // ------------------------------------------------------------------

    private static Fg queryUsageStats() {
        Context context = HookRuntime.appContext;
        if (context == null) {
            return null;
        }
        long now = SystemClock.elapsedRealtime();
        if (now < usageRetryAt) {
            return null;
        }
        try {
            UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) {
                usageRetryAt = now + 60000L;
                return null;
            }
            long wallNow = System.currentTimeMillis();
            UsageEvents events = usm.queryEvents(wallNow - 60000L, wallNow);
            if (events == null) {
                usageRetryAt = now + 30000L;
                return null;
            }
            long bestTs = 0L;
            String bestPkg = null;
            String bestCls = null;
            UsageEvents.Event event = new UsageEvents.Event();
            while (events.hasNextEvent()) {
                events.getNextEvent(event);
                if (event.getEventType() != UsageEvents.Event.ACTIVITY_RESUMED) {
                    continue;
                }
                long ts = event.getTimeStamp();
                if (ts >= bestTs) {
                    bestTs = ts;
                    bestPkg = event.getPackageName();
                    bestCls = event.getClassName();
                }
            }
            if (bestPkg == null) {
                lastError = lastError.length() == 0 ? "UsageStats 无 ACTIVITY_RESUMED 事件（可能没有 PACKAGE_USAGE_STATS 权限）" : lastError;
                usageRetryAt = now + 30000L;
                return null;
            }
            return new Fg(bestPkg, bestCls, "usage-stats");
        } catch (Throwable t) {
            lastError = "UsageStats: " + t.getClass().getSimpleName();
            usageRetryAt = now + 60000L;
            Logx.w("UsageStats 前台查询失败", t);
            return null;
        }
    }
}
