package com.edgeguard.gesture.xposed;

import android.content.Context;
import android.content.res.Resources;
import android.view.WindowManager;
import android.view.WindowMetrics;

import com.edgeguard.gesture.common.Logx;
import com.edgeguard.gesture.common.ModuleConfig;

/**
 * 顶部 / 底部"极边缘区"判定（全部是内存操作，绝不 IO / Binder）。
 *
 * 屏幕高度来源（按可靠性排序，全部是公开 API，不碰 @hide）：
 *  1) WindowManager.getCurrentWindowMetrics().getBounds().height()  —— API 30+，SystemUI 的 window 是整屏；
 *  2) Resources.getSystem().getDisplayMetrics().heightPixels        —— 兜底；
 *  3) 写死 2400  —— 最后兜底（宁可判错也不抛异常）。
 *
 * 为什么不直接用 MotionEvent.getDisplayId()：那是 @hide，android.jar 里取不到；
 * 而 SystemUI 的 shade / 导航条窗口都在 display 0，用整屏高度即可。
 *
 * 缓存策略：高度只缓存 60 秒；若某次事件的 rawY 比缓存值还大（旋转/分辨率变化），立即重算。
 * 这样触摸热路径上最多每 60 秒做一次取窗口尺寸的操作，其余全是两次比较。
 */
final class Zones {

    static final int ZONE_NONE = 0;
    static final int ZONE_TOP = 1;
    static final int ZONE_BOTTOM = 2;

    private static final long CACHE_TTL_MS = 60000L;
    private static final Object LOCK = new Object();

    private static volatile int cachedHeight;
    private static volatile long cachedAt;
    private static volatile int lastLoggedHeight = -1;

    private Zones() {
    }

    /** 当前屏幕高度（px）。 */
    static int screenHeight() {
        int height = cachedHeight;
        long now = android.os.SystemClock.elapsedRealtime();
        if (height > 0 && now - cachedAt < CACHE_TTL_MS) {
            return height;
        }
        synchronized (LOCK) {
            now = android.os.SystemClock.elapsedRealtime();
            if (cachedHeight > 0 && now - cachedAt < CACHE_TTL_MS) {
                return cachedHeight;
            }
            int computed = computeHeight();
            cachedHeight = computed;
            cachedAt = now;
            if (computed != lastLoggedHeight) {
                lastLoggedHeight = computed;
                Logx.i("screen metrics: height=" + computed + "px");
            }
            return computed;
        }
    }

    /** 事件里的 rawY 超过缓存高度时（旋转/分辨率变化）强制重算一次。 */
    static void maybeRefresh(float rawY) {
        try {
            int height = cachedHeight;
            if (height <= 0 || rawY > height + 1f) {
                synchronized (LOCK) {
                    cachedAt = 0L;
                }
                screenHeight();
            }
        } catch (Throwable ignored) {
        }
    }

    static void invalidate() {
        synchronized (LOCK) {
            cachedAt = 0L;
        }
    }

    private static int computeHeight() {
        Context context = HookRuntime.anyContext();
        if (context != null) {
            try {
                WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
                if (wm != null) {
                    WindowMetrics metrics = wm.getCurrentWindowMetrics();
                    if (metrics != null && metrics.getBounds() != null) {
                        int h = metrics.getBounds().height();
                        if (h > 0) {
                            return h;
                        }
                    }
                }
            } catch (Throwable t) {
                Logx.w("getCurrentWindowMetrics failed（回退到 Resources）", t);
            }
        }
        try {
            int h = Resources.getSystem().getDisplayMetrics().heightPixels;
            if (h > 0) {
                return h;
            }
        } catch (Throwable ignored) {
        }
        return 2400;
    }

    /** 触摸起点是否落在顶部屏蔽区。 */
    static boolean inTopZone(float rawY, int screenHeight, float ratio) {
        if (screenHeight <= 0 || ratio <= 0f) {
            return false;
        }
        return rawY <= screenHeight * ratio + 0.5f;
    }

    /** 触摸起点是否落在底部屏蔽区。 */
    static boolean inBottomZone(float rawY, int screenHeight, float ratio) {
        if (screenHeight <= 0 || ratio <= 0f) {
            return false;
        }
        return rawY >= screenHeight * (1f - ratio) - 0.5f;
    }

    static int maskOf(float rawY, ModuleConfig config) {
        ModuleConfig cfg = config == null ? ModuleConfig.DEFAULT : config;
        int height = screenHeight();
        int mask = ZONE_NONE;
        if (inTopZone(rawY, height, cfg.topZoneRatio)) {
            mask |= ZONE_TOP;
        }
        if (inBottomZone(rawY, height, cfg.bottomZoneRatio)) {
            mask |= ZONE_BOTTOM;
        }
        return mask;
    }

    static String describe(ModuleConfig config, int height) {
        ModuleConfig cfg = config == null ? ModuleConfig.DEFAULT : config;
        return "top=" + Math.round(cfg.topZoneRatio * 100) + "%("
                + Math.round(height * cfg.topZoneRatio) + "px)"
                + " bottom=" + Math.round(cfg.bottomZoneRatio * 100) + "%("
                + Math.round(height * cfg.bottomZoneRatio) + "px)"
                + " height=" + height + "px";
    }
}
