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

    /** 唯一作用域：System UI */
    public static final String SYSTEMUI_PACKAGE = "com.android.systemui";

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
