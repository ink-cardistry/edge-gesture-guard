package com.edgeguard.gesture.common;

import android.net.Uri;

/**
 * 模块内所有跨进程约定集中在这里（App 进程 <-> SystemUI 进程）。
 *
 * IPC 设计说明（Android 11+ 现实约束）：
 *  - 传统做法是 Hook 进程直接读模块 App 的 SharedPreferences（XSharedPreferences），
 *    但 Android 11 之后 /data/data/<pkg> 目录权限 + SELinux 使得 SystemUI(platform_app)
 *    基本读不到别的应用私有目录，因此不作为主通道；
 *  - 主通道：App 主动 sendBroadcast（显式 setPackage("com.android.systemui")）把规则 JSON
 *    推给 SystemUI 内动态注册的接收器，SystemUI 再缓存到自己的私有目录（重启可恢复）；
 *  - 兜底通道：ContentProvider（SystemUI 主动 query），用于 SystemUI 冷启动时拉取最新规则；
 *  - 反向通道：SystemUI 推送状态报告给 App（哪些 Hook 成功、前台检测走哪一档）。
 */
public final class ModuleContract {

    /** 模块自身包名（与 build.gradle 的 applicationId 保持一致） */
    public static final String MODULE_PACKAGE = "com.edgeguard.gesture";

    /** 默认（也是唯一必需）作用域：System UI */
    public static final String SYSTEMUI_PACKAGE = "com.android.systemui";

    /**
     * 可选作用域：桌面 / Launcher（策略 B 专用，默认关闭）。
     *
     * 为什么需要：AOSP 16/17 的底部上滑由 Launcher 进程的
     * com.android.quickstep.TouchInteractionService 执行，SystemUI 里没有最终执行逻辑。
     * 用户只有手工把作用域扩展到这里、并在 UI 里打开开关，策略 B 才可能生效。
     * 清单里**不预填**这些作用域（xposedscope 只声明 SystemUI），确保默认视角最小。
     */
    public static final String[] LAUNCHER_PACKAGES = {
            "com.android.launcher3",
            "com.google.android.apps.nexuslauncher",
            "com.android.launcher",
            "com.miui.home",
            "com.bbk.launcher2",
            "com.oppo.launcher",
            "com.huawei.android.launcher",
    };

    /** 是否是我们认识的桌面/Launcher 包名。 */
    public static boolean isLauncherPackage(String packageName) {
        if (packageName == null) {
            return false;
        }
        for (String name : LAUNCHER_PACKAGES) {
            if (name.equals(packageName)) {
                return true;
            }
        }
        return false;
    }

    /** 规则 Provider authority */
    public static final String AUTHORITY = MODULE_PACKAGE + ".rules";
    public static final Uri RULES_URI = Uri.parse("content://" + AUTHORITY + "/rules");

    /** App -> SystemUI：规则全量推送 */
    public static final String ACTION_RULES_PUSH = MODULE_PACKAGE + ".action.RULES_PUSH";
    /** SystemUI -> App：请求重新推送规则（SystemUI 启动/重启时） */
    public static final String ACTION_RULES_REQUEST = MODULE_PACKAGE + ".action.RULES_REQUEST";
    /** SystemUI -> App：状态报告 */
    public static final String ACTION_STATUS_PUSH = MODULE_PACKAGE + ".action.STATUS_PUSH";
    /** App -> SystemUI：请求状态报告 */
    public static final String ACTION_STATUS_REQUEST = MODULE_PACKAGE + ".action.STATUS_REQUEST";
    /** App -> SystemUI：前台应用变化（无障碍助手推送） */
    public static final String ACTION_FOREGROUND_PUSH = MODULE_PACKAGE + ".action.FOREGROUND_PUSH";

    /**
     * App -> SystemUI：「游戏模式」会话总闸（启用/退出）。
     *
     * 为什么总闸放在 SystemUI 里而不是 App 的 SharedPreferences：
     *  - SystemUI 常驻，一定能收到 SCREEN_OFF / USER_PRESENT，锁屏后解锁自动退出才不会漏；
     *  - App 进程随时可能被冻结/杀死，放 App 会出现"已经锁屏解锁了但还在拦"的鬼状态。
     * App 只是"遥控器"：发命令 + 显示状态。
     */
    public static final String ACTION_ARM = MODULE_PACKAGE + ".action.ARM";
    public static final String ACTION_DISARM = MODULE_PACKAGE + ".action.DISARM";

    /** 常驻通知上的按钮（App 内部显式广播，不导出） */
    public static final String ACTION_NOTIFY_EXIT = MODULE_PACKAGE + ".action.NOTIFY_EXIT";
    public static final String ACTION_NOTIFY_TOGGLE = MODULE_PACKAGE + ".action.NOTIFY_TOGGLE";

    /**
     * Launcher 进程 -> App：策略 B 的注入状态（走 ContentProvider.update）。
     * 为什么需要它：策略 B 跑在桌面进程里，它的日志只在 logcat，App 看不到；
     * 没有这个回报，用户无法判断"到底加没加作用域、挂上没有"。
     */
    public static final String COLUMN_LAUNCHER_STATUS = "launcher_status";
    public static final String PREF_LAUNCHER_STATUS = "launcher_status";
    /** 桌面进程自己的诊断日志尾部（同样经 Provider 回报，App 落盘后 AI 可直接读） */
    public static final String COLUMN_LAUNCHER_DIAG = "launcher_diag";
    public static final String PREF_LAUNCHER_DIAG = "launcher_diag";

    /** App 侧"最近一次已知"的游戏模式状态（权威值永远来自 SystemUI 的状态回推） */
    public static final String PREF_ARMED = "armed";
    public static final String PREF_ARMED_SOURCE = "armed_source";

    /** 常驻通知 */
    public static final int NOTIF_ID_GAME_MODE = 0x45444701;
    public static final String CHANNEL_GAME_MODE = "edgeguard_game_mode";

    public static final String EXTRA_RULES_JSON = "rules_json";
    public static final String EXTRA_STATUS_JSON = "status_json";
    public static final String EXTRA_PACKAGE = "fg_package";
    public static final String EXTRA_ACTIVITY = "fg_activity";

    /** App 侧 SharedPreferences */
    public static final String PREFS_RULES = "edgeguard_rules";
    public static final String PREF_RULES_JSON = "rules_json";
    public static final String PREF_STATUS_JSON = "status_json";

    /**
     * SystemUI 侧自检记录（写在 Settings.Global）。
     *
     * 为什么需要它：如果模块没有被 LSPosed 注入，广播/Provider 全部不通，
     * UI 只能看到"正在请求状态…"，无法区分"没注入"和"注入了但通道不通"。
     * Hook 端每次状态变化会写这个 key（SystemUI 有 WRITE_SECURE_SETTINGS），
     * 而 App 读 Settings.Global 不需要任何权限 —— 于是 UI 能直接自证：
     *   读到 -> 已注入；读不到 -> 未被注入。
     */
    public static final String DIAG_GLOBAL_KEY = "edge_gesture_guard_diag";

    /**
     * 更早期的注入标记：模块类一被 LSPosed 加载就写这个 key（早于 Application 创建）。
     * 用来区分两种失败：
     *   - 连这个都没有 -> LSPosed 根本没把模块加载进 SystemUI（作用域 / Zygisk 卸载 / 兼容性）
     *   - 有它但没有 DIAG -> 模块加载了，但初始化阶段出错
     */
    public static final String LOADED_GLOBAL_KEY = "edge_gesture_guard_loaded";

    private ModuleContract() {
    }
}
