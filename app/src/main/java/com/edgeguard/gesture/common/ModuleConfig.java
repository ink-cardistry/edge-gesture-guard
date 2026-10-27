package com.edgeguard.gesture.common;

import org.json.JSONObject;

/**
 * 模块级配置（不是"每个应用"的规则，而是全局几何/调试参数）。
 *
 * 为什么放在 common 而不是 xposed：
 *  - App 进程要能编辑它（阈值滑杆 / 验证模式开关），Hook 进程要能无锁读取它；
 *  - 它跟规则表一起装在同一个 JSON 里推过去（{@link RuleSet} 的 "config" 字段），
 *    所以只需要一条已有的 IPC 通道，不新增广播、不新增轮询。
 *
 * 几何参数含义（都是"屏幕高度的百分比"，从屏幕边算起）：
 *  - topZoneRatio    ：顶部屏蔽区占屏幕高度的比例（默认 12%）；
 *  - bottomZoneRatio ：底部屏蔽区占屏幕高度的比例（默认 12%）。
 * 取 2% ~ 30% 之外的值一律视为非法，回退默认值 —— 这是安全阀：
 * 一个 0.5 的阈值会让整个屏幕变成"边缘"，音游中间判定区会被误伤。
 *
 * launcherBottom：策略 B（Hook 桌面进程 TouchInteractionService）的总开关，**默认 false**。
 * 只有用户手动把本模块作用域扩展到 Launcher、并在 UI 里打开它，才可能生效。
 */
public final class ModuleConfig {

    public static final float DEFAULT_TOP_RATIO = 0.12f;
    public static final float DEFAULT_BOTTOM_RATIO = 0.12f;
    public static final float MIN_RATIO = 0.02f;
    public static final float MAX_RATIO = 0.30f;

    public static final ModuleConfig DEFAULT =
            new ModuleConfig(DEFAULT_TOP_RATIO, DEFAULT_BOTTOM_RATIO, false, false);

    /** 顶部屏蔽区比例（0.02 ~ 0.30） */
    public final float topZoneRatio;
    /** 底部屏蔽区比例（0.02 ~ 0.30） */
    public final float bottomZoneRatio;
    /** true = 只记录日志、不实际拦截（"先日志、后拦截"的第一步） */
    public final boolean verifyOnly;
    /** true = 允许 Launcher 进程内的底部 Hook（策略 B，需手工扩展作用域） */
    public final boolean launcherBottom;
    /**
     * 策略 A 是否真的吞掉底部事件。**默认 false**。
     *
     * 实测结论：在 SystemUI 的返回手势 monitor 上消费底部事件，**拦不住** Launcher 的
     * 上滑 Home（monitor 之间不互斥），但会让游戏窗口收不到底部 12% 的触摸 ——
     * 对音游是纯亏。所以默认只观测计数，真正的拦截走策略 B。
     */
    public final boolean bottomConsume;
    /**
     * 锁屏→解锁后是否**保持**游戏模式（默认 false = 自动退出）。
     * 默认值沿用你的原始需求；但打音游时"锁一下屏再回来"很常见，
     * 自动退出会让人以为"没生效"，所以给一个开关。
     */
    public final boolean keepOnUnlock;
    /** 游戏模式期间隐藏顶部状态栏（默认 true）。1.6.2 里意外出现过这个效果，用户要求保留。 */
    public final boolean hideStatusBar;

    public ModuleConfig(float topZoneRatio, float bottomZoneRatio,
                        boolean verifyOnly, boolean launcherBottom) {
        this(topZoneRatio, bottomZoneRatio, verifyOnly, launcherBottom, false, false);
    }

    public ModuleConfig(float topZoneRatio, float bottomZoneRatio, boolean verifyOnly,
                        boolean launcherBottom, boolean bottomConsume) {
        this(topZoneRatio, bottomZoneRatio, verifyOnly, launcherBottom, bottomConsume, false);
    }

    public ModuleConfig(float topZoneRatio, float bottomZoneRatio, boolean verifyOnly,
                        boolean launcherBottom, boolean bottomConsume, boolean keepOnUnlock) {
        this(topZoneRatio, bottomZoneRatio, verifyOnly, launcherBottom, bottomConsume,
                keepOnUnlock, true);
    }

    public ModuleConfig(float topZoneRatio, float bottomZoneRatio, boolean verifyOnly,
                        boolean launcherBottom, boolean bottomConsume, boolean keepOnUnlock,
                        boolean hideStatusBar) {
        this.topZoneRatio = sane(topZoneRatio, DEFAULT_TOP_RATIO);
        this.bottomZoneRatio = sane(bottomZoneRatio, DEFAULT_BOTTOM_RATIO);
        this.verifyOnly = verifyOnly;
        this.launcherBottom = launcherBottom;
        this.bottomConsume = bottomConsume;
        this.keepOnUnlock = keepOnUnlock;
        this.hideStatusBar = hideStatusBar;
    }

    private static float sane(float value, float fallback) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            return fallback;
        }
        if (value < MIN_RATIO || value > MAX_RATIO) {
            return fallback;
        }
        return value;
    }

    public ModuleConfig withTopRatio(float value) {
        return new ModuleConfig(value, bottomZoneRatio, verifyOnly, launcherBottom,
                bottomConsume, keepOnUnlock);
    }

    public ModuleConfig withBottomRatio(float value) {
        return new ModuleConfig(topZoneRatio, value, verifyOnly, launcherBottom,
                bottomConsume, keepOnUnlock);
    }

    public ModuleConfig withVerifyOnly(boolean value) {
        return new ModuleConfig(topZoneRatio, bottomZoneRatio, value, launcherBottom,
                bottomConsume, keepOnUnlock);
    }

    public ModuleConfig withLauncherBottom(boolean value) {
        return new ModuleConfig(topZoneRatio, bottomZoneRatio, verifyOnly, value,
                bottomConsume, keepOnUnlock);
    }

    public ModuleConfig withBottomConsume(boolean value) {
        return new ModuleConfig(topZoneRatio, bottomZoneRatio, verifyOnly, launcherBottom,
                value, keepOnUnlock);
    }

    public ModuleConfig withKeepOnUnlock(boolean value) {
        return new ModuleConfig(topZoneRatio, bottomZoneRatio, verifyOnly, launcherBottom,
                bottomConsume, value, hideStatusBar);
    }

    public ModuleConfig withHideStatusBar(boolean value) {
        return new ModuleConfig(topZoneRatio, bottomZoneRatio, verifyOnly, launcherBottom,
                bottomConsume, keepOnUnlock, value);
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("top", round(topZoneRatio));
            o.put("bottom", round(bottomZoneRatio));
            o.put("verify", verifyOnly);
            o.put("launcher", launcherBottom);
            o.put("bottomConsume", bottomConsume);
            o.put("keepOnUnlock", keepOnUnlock);
            o.put("hideStatusBar", hideStatusBar);
        } catch (Throwable ignored) {
            // JSONObject.put 只在 key 为 null 时抛，这里不会发生
        }
        return o;
    }

    public static ModuleConfig fromJson(JSONObject o) {
        if (o == null) {
            return DEFAULT;
        }
        try {
            return new ModuleConfig(
                    (float) o.optDouble("top", DEFAULT_TOP_RATIO),
                    (float) o.optDouble("bottom", DEFAULT_BOTTOM_RATIO),
                    o.optBoolean("verify", false),
                    o.optBoolean("launcher", false),
                    o.optBoolean("bottomConsume", false),
                    o.optBoolean("keepOnUnlock", false),
                    o.optBoolean("hideStatusBar", true));
        } catch (Throwable t) {
            return DEFAULT;
        }
    }

    public void writeTo(JSONObject target) {
        if (target == null) {
            return;
        }
        try {
            target.put("config", toJson());
        } catch (Throwable ignored) {
        }
    }

    private static double round(float value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    public String describe() {
        return "top=" + Math.round(topZoneRatio * 100) + "%"
                + " bottom=" + Math.round(bottomZoneRatio * 100) + "%"
                + (verifyOnly ? " 验证模式(只记日志)" : "")
                + (launcherBottom ? " LauncherHook=开" : "")
                + (bottomConsume ? " 策略A吞事件" : "")
                + (keepOnUnlock ? " 锁屏保持" : "");
    }

    @Override
    public String toString() {
        return "ModuleConfig(" + describe() + ")";
    }
}
