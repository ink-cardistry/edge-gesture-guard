package com.edgeguard.gesture.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.edgeguard.gesture.R;
import com.edgeguard.gesture.common.Logx;
import com.edgeguard.gesture.common.ModuleBroadcasts;
import com.edgeguard.gesture.common.ModuleConfig;
import com.edgeguard.gesture.common.ModuleContract;
import com.edgeguard.gesture.common.RuleSet;
import com.edgeguard.gesture.common.RuleStore;
import com.edgeguard.gesture.common.StatusReport;

import java.util.List;

/**
 * 控制器 UI（纯 framework 控件，不依赖 AndroidX，方便离线/CI 构建）。
 *
 * 流程：
 *   打开 -> 下拉应用列表 -> 点一行展开 -> 勾选"左/右/底" -> 立即写 SharedPreferences
 *        -> 广播给 SystemUI -> Hook 内存规则表整体替换 -> 下一次触摸立刻生效。
 */
public final class MainActivity extends Activity implements StatusBus.Listener {

    private EditText searchView;
    private CheckBox includeSystemView;
    private ListView listView;
    private TextView statusView;
    private TextView statusBrief;
    private TextView statusToggle;
    private ScrollView statusScroll;
    private TextView emptyView;
    private Button gameModeButton;
    /** 详情区是否展开（默认收起：状态日志不再把下面的应用列表顶出屏幕）。 */
    private boolean detailExpanded;

    private AppListAdapter adapter;
    private RuleStore store;
    private boolean includeSystem;
    /** 最近一次状态报告（音游预设要用它拿"当前前台应用"）。 */
    private StatusReport lastReport;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private volatile boolean statusReceived;

    /**
     * 4 秒没收到 SystemUI 的任何状态回报 -> 直接告诉用户到底卡在哪一层。
     * 光显示"正在请求"会让用户完全无法判断是没注入、广播不通、还是权限问题。
     */
    private final Runnable statusTimeout = new Runnable() {
        @Override
        public void run() {
            if (statusReceived) {
                return;
            }
            renderNoResponse();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_main);
            store = new RuleStore(this);

            searchView = findViewById(R.id.search);
            includeSystemView = findViewById(R.id.include_system);
            listView = findViewById(R.id.app_list);
            statusView = findViewById(R.id.status_view);
            statusBrief = findViewById(R.id.status_brief);
            statusToggle = findViewById(R.id.status_toggle);
            statusScroll = findViewById(R.id.status_scroll);
            emptyView = findViewById(R.id.empty_view);
            gameModeButton = findViewById(R.id.btn_game_mode);

            statusScroll.setVisibility(detailExpanded ? View.VISIBLE : View.GONE);
            statusToggle.setText(detailExpanded
                    ? R.string.status_hide_detail : R.string.status_show_detail);
            statusToggle.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggleStatusDetail();
                }
            });
            statusBrief.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggleStatusDetail();
                }
            });
            gameModeButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    applyGameMode(!currentArmed());
                }
            });
            ensureNotificationPermission();

            adapter = new AppListAdapter(this, store, new AppListAdapter.Listener() {
                @Override
                public void onRulesChanged(String packageName, boolean blockLeft,
                                           boolean blockRight, boolean blockBottom,
                                           boolean blockTopShade) {
                    RuleSet rules = store.load();
                    ModuleBroadcasts.pushRules(MainActivity.this, rules);
                    Logx.i("rule changed: " + packageName
                            + " L=" + blockLeft + " R=" + blockRight + " B=" + blockBottom
                            + " T=" + blockTopShade
                            + " total=" + rules.size());
                }
            });
            listView.setAdapter(adapter);
            listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                @Override
                public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                    adapter.toggle(position);
                }
            });

            searchView.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                }

                @Override
                public void afterTextChanged(Editable s) {
                    adapter.setQuery(s == null ? "" : s.toString());
                    updateEmptyView();
                }
            });

            includeSystemView.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    includeSystem = isChecked;
                    loadApps();
                }
            });

            Button syncButton = findViewById(R.id.btn_sync);
            syncButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ModuleBroadcasts.pushRules(MainActivity.this, store.load());
                    ModuleBroadcasts.requestStatus(MainActivity.this);
                    Toast.makeText(MainActivity.this, "已重新同步规则并请求状态", Toast.LENGTH_SHORT).show();
                }
            });

            Button accessibilityButton = findViewById(R.id.btn_accessibility);
            accessibilityButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openAccessibilitySettings();
                }
            });

            Button presetButton = findViewById(R.id.btn_game_preset);
            presetButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    applyGamePreset();
                }
            });

            Button tuningButton = findViewById(R.id.btn_tuning);
            tuningButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showTuningDialog();
                }
            });

            includeSystem = includeSystemView.isChecked();
            updateGameModeButton(currentArmed());
            loadApps();
        } catch (Throwable t) {
            Logx.e("MainActivity.onCreate failed", t);
            Toast.makeText(this, "初始化失败，请查看 logcat（TAG=EdgeGestureGuard）", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        StatusBus.setListener(this);
        statusReceived = false;
        uiHandler.removeCallbacks(statusTimeout);
        uiHandler.postDelayed(statusTimeout, 4000L);
        try {
            // SystemUI 可能刚重启，主动补推一次规则
            ModuleBroadcasts.pushRules(this, store.load());
            ModuleBroadcasts.requestStatus(this);
            // 通知/磁贴的显示跟随"最近一次已知状态"，随后会被 SystemUI 的回推纠正
            com.edgeguard.gesture.app.ArmNotifier.refresh(this, currentArmed());
            StatusReport last = StatusBus.last(this);
            if (last != null) {
                renderStatus(last);
            }
        } catch (Throwable t) {
            Logx.w("onResume sync failed", t);
        }
    }

    @Override
    protected void onPause() {
        StatusBus.setListener(null);
        uiHandler.removeCallbacks(statusTimeout);
        super.onPause();
    }

    @Override
    public void onStatus(StatusReport report) {
        statusReceived = true;
        uiHandler.removeCallbacks(statusTimeout);
        renderStatus(report);
    }

    private void loadApps() {
        try {
            emptyView.setVisibility(View.GONE);
            new AppListLoader(this).loadAsync(includeSystem, new AppListLoader.Callback() {
                @Override
                public void onLoaded(List<AppEntry> apps) {
                    adapter.setData(apps);
                    updateEmptyView();
                }
            });
        } catch (Throwable t) {
            Logx.w("loadApps failed", t);
        }
    }

    private void updateEmptyView() {
        try {
            emptyView.setVisibility(adapter.getCount() == 0 ? View.VISIBLE : View.GONE);
        } catch (Throwable ignored) {
        }
    }

    private void renderStatus(StatusReport report) {
        if (report == null) {
            return;
        }
        try {
            lastReport = report;
            adapter.setSupport(report.bottomZoneObserved(), report.shadeUsable());
            StringBuilder sb = new StringBuilder();
            sb.append(getString(R.string.status_title)).append('\n');
            if (report.edgeHooked) {
                sb.append("· 左右返回 Hook：已挂载\n    ").append(report.edgeTarget);
            } else {
                sb.append("· 左右返回 Hook：未挂载\n    ").append(report.edgeTarget);
            }
            sb.append('\n');
            // 功能二：通知栏下拉（SystemUI 进程内实现）
            if (report.shadeUsable()) {
                sb.append("· 通知栏下拉 Hook：已挂载\n    ").append(report.shadeTarget);
            } else {
                sb.append("· 通知栏下拉 Hook：未挂载（当前 ROM 上此项不生效）\n    ")
                        .append(report.shadeTarget);
            }
            sb.append('\n');
            // 功能一：底部上滑 —— 只报实测结论，不报"希望"
            sb.append("· 底部上滑（策略 A）：").append(report.bottomZoneObserved()
                    ? "输入通道已观测到底部事件 → 有条件生效，请实测上滑是否仍回桌面"
                    : "输入通道未观测到底部事件 → 当前 ROM 上策略 A 不可用");
            sb.append("\n    实测计数：").append(
                    report.bottomMeasure == null || report.bottomMeasure.length() == 0
                            ? "无" : report.bottomMeasure);
            sb.append("\n    HomeHandle 入口：").append(report.bottomUsable() ? "已挂载" : "未挂载");
            if (report.bottomTarget != null && report.bottomTarget.length() > 0) {
                sb.append("（").append(report.bottomTarget).append("）");
            }
            if (report.launcherState != null && report.launcherState.length() > 0) {
                sb.append("\n    ").append(report.launcherState);
            }
            if (report.zoneConfig != null && report.zoneConfig.length() > 0) {
                sb.append('\n').append("· 边缘区：").append(report.zoneConfig);
                if (report.verifyOnly) {
                    sb.append("  ⚠ 验证模式：只记日志、不拦截");
                }
            }
            sb.append('\n').append("· 前台应用来源：").append(strategyName(report.foregroundStrategy));
            if (report.foregroundPackage != null && report.foregroundPackage.length() > 0) {
                sb.append("\n    最近前台：").append(report.foregroundPackage);
                if (report.foregroundActivity != null && report.foregroundActivity.length() > 0) {
                    sb.append('/').append(report.foregroundActivity);
                }
            }
            if (report.foregroundError != null && report.foregroundError.length() > 0) {
                sb.append("\n    提示：").append(report.foregroundError);
            }
            sb.append('\n').append("· Hook 内规则条数：").append(report.ruleCount);
            if (report.edgeMetrics != null && report.edgeMetrics.length() > 0) {
                sb.append('\n').append("· 边缘几何：").append(report.edgeMetrics);
            }

            // 可执行的告警（不用猜，照着做即可）
            if (!report.edgeHooked) {
                sb.append("\n\n⚠ 未挂载任何手势入口：请确认 LSPosed 已启用本模块、作用域只勾 SystemUI、"
                        + "并且重启过 SystemUI/手机。");
            }
            if (report.ruleCount == 0) {
                sb.append("\n⚠ Hook 内规则条数为 0：规则没送到 SystemUI（点下方「重新同步到 SystemUI」；"
                        + "若仍为 0，可能模块被冻结或 SystemUI 未重启）。");
            }
            if (!report.shadeUsable()) {
                sb.append("\n⚠ 通知栏下拉 Hook 未挂载：logcat 里的 'shade candidates' 行会列出"
                        + "每个候选类的探测结果，把该行贴出来即可补候选名。");
            }
            if ("none".equals(report.foregroundStrategy) || report.foregroundStrategy == null) {
                sb.append("\n⚠ 前台应用识别失败，此时按设计「不屏蔽」。请点「前台检测助手」启用无障碍助手。");
            }
            appendSelfCheck(sb);
            statusView.setText(sb.toString());
            renderBrief(report);
            updateGameModeButton(report.armed);
        } catch (Throwable t) {
            Logx.w("renderStatus failed", t);
        }
    }

    // ------------------------------------------------------------------
    // 状态摘要（默认收起详情，避免日志把列表顶出屏幕）
    // ------------------------------------------------------------------

    private void renderBrief(StatusReport report) {
        try {
            StringBuilder b = new StringBuilder();
            b.append("左右:").append(report.edgeHooked ? "✅" : "❌")
                    .append("  顶部:").append(report.shadeUsable() ? "✅" : "❌")
                    .append("  底部:").append(report.bottomZoneObserved() ? "✅" : "⚠")
                    .append("   规则:").append(report.ruleCount);
            b.append('\n').append("游戏模式:").append(report.armed ? "已开启" : "已关闭");
            if (report.armed) {
                b.append("（锁屏解锁自动退出）");
            }
            String pkg = report.foregroundPackage;
            if (pkg != null && pkg.length() > 0) {
                b.append("  ·  前台:").append(pkg);
            }
            int warnings = warningCount(report);
            if (warnings > 0) {
                b.append('\n').append("⚠ ").append(warnings).append(" 条提示 — 点「详情」查看");
            }
            statusBrief.setText(b.toString());
        } catch (Throwable t) {
            Logx.w("renderBrief failed", t);
        }
    }

    private int warningCount(StatusReport report) {
        int n = 0;
        if (!report.edgeHooked) {
            n++;
        }
        if (!report.shadeUsable()) {
            n++;
        }
        if (!report.bottomZoneObserved()) {
            n++;
        }
        if (report.ruleCount == 0) {
            n++;
        }
        if (report.foregroundStrategy == null || "none".equals(report.foregroundStrategy)) {
            n++;
        }
        return n;
    }

    private void toggleStatusDetail() {
        try {
            detailExpanded = !detailExpanded;
            statusScroll.setVisibility(detailExpanded ? View.VISIBLE : View.GONE);
            statusToggle.setText(detailExpanded
                    ? R.string.status_hide_detail : R.string.status_show_detail);
        } catch (Throwable t) {
            Logx.w("toggleStatusDetail failed", t);
        }
    }

    // ------------------------------------------------------------------
    // 游戏模式（会话总闸）：通知 / 磁贴 / 本按钮 三个入口等价
    // ------------------------------------------------------------------

    private boolean currentArmed() {
        StatusReport report = lastReport;
        if (report != null) {
            return report.armed;
        }
        return ArmClient.isArmed(this);
    }

    private void applyGameMode(boolean armed) {
        try {
            ArmClient.setArmed(this, armed, "app");
            updateGameModeButton(armed);
            Toast.makeText(this, armed
                            ? R.string.game_mode_on_toast
                            : R.string.game_mode_off_toast,
                    Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Logx.w("applyGameMode failed", t);
        }
    }

    private void updateGameModeButton(boolean armed) {
        try {
            gameModeButton.setText(armed
                    ? R.string.btn_game_mode_on : R.string.btn_game_mode_off);
        } catch (Throwable t) {
            Logx.w("updateGameModeButton failed", t);
        }
    }

    /** API 33+ 才有 POST_NOTIFICATIONS；没有它"寄生通知"不会显示（磁贴与锁屏退出仍可用）。 */
    private void ensureNotificationPermission() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(
                            new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1001);
                }
            }
        } catch (Throwable t) {
            Logx.w("ensureNotificationPermission failed", t);
        }
    }

    private String strategyName(String strategy) {
        if (strategy == null || strategy.length() == 0 || "none".equals(strategy)) {
            return "未知（安全默认：不屏蔽）";
        }
        if (strategy.startsWith("atm") || strategy.startsWith("am:")) {
            return "ActivityTaskManager 反射查询";
        }
        if (strategy.startsWith("accessibility")) {
            return "无障碍助手推送";
        }
        if (strategy.startsWith("usage")) {
            return "UsageStatsManager 兜底";
        }
        return strategy;
    }

    /** SystemUI 侧自检记录（Settings.Global，由 Hook 端写入；App 读取不需要任何权限）。 */
    private String readDiag() {
        try {
            String v = Settings.Global.getString(getContentResolver(), ModuleContract.DIAG_GLOBAL_KEY);
            return (v == null || v.length() == 0) ? null : v;
        } catch (Throwable t) {
            return null;
        }
    }

    /** LSPosed Manager 是否装了（模块要生效，这个是前提）。 */
    private boolean hasLsposed() {
        String[] names = {"org.lsposed.manager", "io.github.lsposed.manager", "org.lsposed.lspd"};
        PackageManager pm = getPackageManager();
        for (String name : names) {
            try {
                pm.getPackageInfo(name, 0);
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private String moduleVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    private String readGlobal(String key) {
        try {
            String v = Settings.Global.getString(getContentResolver(), key);
            return (v == null || v.length() == 0) ? null : v;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 读 Hook 端写在「本 App 外部目录」里的执行轨迹（不需要任何权限）。 */
    private String readHookTrace() {
        try {
            java.io.File dir = getExternalFilesDir(null);
            if (dir == null) {
                return null;
            }
            java.io.File file = new java.io.File(dir, "hook-status.txt");
            if (!file.exists()) {
                return null;
            }
            java.io.FileInputStream in = new java.io.FileInputStream(file);
            try {
                byte[] buf = new byte[(int) Math.min(file.length(), 8192)];
                int read = in.read(buf);
                if (read <= 0) {
                    return null;
                }
                return new String(buf, 0, read, "UTF-8");
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /** 只保留轨迹的最后几行，方便直接看结论。 */
    private String tailLines(String text, int maxLines) {
        if (text == null) {
            return null;
        }
        String[] lines = text.trim().split("\n");
        int start = Math.max(0, lines.length - maxLines);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    private void appendSelfCheck(StringBuilder sb) {
        String diag = readDiag();
        String loaded = readGlobal(ModuleContract.LOADED_GLOBAL_KEY);
        sb.append("\n\n—— 自检 ——");
        sb.append("\n· 模块版本：").append(moduleVersion());

        // 判定以「自检记录」为准：它由 Hook 端在拿到真实 App Context 后写入，
        // 而 early marker 在 handleLoadPackage 阶段常因包名/uid 校验写不进去，不能当判据。
        if (diag != null) {
            sb.append("\n· 判定：模块已注入并在 SystemUI 中运行");
        } else if (loaded != null) {
            sb.append("\n· 判定：模块已加载，但 Hook 初始化未完成");
        } else {
            sb.append("\n· 判定：模块未被注入（自检记录与标记都为空）");
        }
        sb.append("\n· SystemUI 自检记录：").append(diag == null ? "无" : diag);

        // 版本一致性检查：SystemUI 里跑的是"进程启动时加载的"那份代码，
        // 装新 APK 不会替换它 —— 这是最容易误判成"新功能没生效"的原因。
        String runningVersion = extractVersion(diag);
        String installedVersion = moduleVersion();
        if (runningVersion != null && installedVersion != null
                && !runningVersion.equals(installedVersion)) {
            sb.append("\n· ⚠ SystemUI 里仍在运行 v").append(runningVersion)
                    .append("，App 是 v").append(installedVersion)
                    .append("\n  → 新版本还没生效：请重启手机（或 root 下 killall com.android.systemui）");
        }
        sb.append("\n· 注入标记：").append(loaded == null
                ? "未写入（早期写入受限，可忽略）" : loaded);
        String trace = tailLines(readHookTrace(), 4);
        sb.append("\n· 轨迹文件：").append(trace == null
                ? "未写入（可忽略，以上一行自检记录为准）" : "");
        if (trace != null) {
            sb.append('\n').append(trace);
        }
    }

    /** 从自检记录里取 v=x.y.z。 */
    private static String extractVersion(String diag) {
        if (diag == null) {
            return null;
        }
        try {
            int index = diag.indexOf("v=");
            if (index < 0) {
                return null;
            }
            int end = index + 2;
            while (end < diag.length()) {
                char c = diag.charAt(end);
                if (c == ';' || Character.isWhitespace(c)) {
                    break;
                }
                end++;
            }
            String version = diag.substring(index + 2, end).trim();
            return version.length() == 0 ? null : version;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 4 秒无回报时渲染的诊断页（不猜，给出可执行清单）。 */
    private void renderNoResponse() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("SystemUI 未响应（4 秒内没有任何状态回报）\n");
            String diag = readDiag();
            String loaded = readGlobal(ModuleContract.LOADED_GLOBAL_KEY);
            if (diag == null && loaded == null) {
                sb.append("\n判定：模块没有在 SystemUI 里运行起来（自检记录为空）。\n");
                sb.append("\n请依次检查：\n");
                sb.append("1) LSPosed 是否真的装了：下方「LSPosed Manager」一行；\n");
                sb.append("2) LSPosed → 模块 → 打开「边缘手势守卫」总开关；\n");
                sb.append("3) 点进本模块 → 作用域 → 勾选 System UI；\n");
                sb.append("4) 勾完必须重启手机（或重启 SystemUI）；\n");
                sb.append("5) Android 17 需要支持该版本的 LSPosed 分支。\n");
            } else if (diag == null) {
                sb.append("\n判定：模块**已加载**进 SystemUI（注入标记：").append(loaded).append("），\n");
                sb.append("但 Hook 初始化没跑完（没有自检记录）。\n");
                sb.append("\n请在 LSPosed → 日志 里找 com.edgeguard.gesture 的报错行。\n");
            } else {
                sb.append("\n判定：模块已注入 SystemUI（自检记录存在），问题在广播通道。\n");
                sb.append("记录：").append(diag).append("\n");
                sb.append("\n请点下方「重新同步到 SystemUI」；仍不同步时本模块会自动改用\n");
                sb.append("ContentProvider 轮询拉取规则（约 15 秒一次）。\n");
            }
            appendSelfCheck(sb);
            statusView.setText(sb.toString());
        } catch (Throwable t) {
            Logx.w("renderNoResponse failed", t);
        }
    }

    // ------------------------------------------------------------------
    // 音游快捷预设 / 阈值与验证模式
    // ------------------------------------------------------------------

    /**
     * 音游一键预设：对**当前前台应用**打开「屏蔽顶部下拉 + 底部上滑」，
     * 左右边缘保持用户原有设置（不去动它，音游可能需要左右滑动）。
     *
     * 为什么用"当前前台应用"而不是让用户再选一遍：状态报告里已经带了前台包名
     * （SystemUI 跑完一次手势/或切应用后就有），点一下即可，少一次误选。
     */
    private void applyGamePreset() {
        try {
            StatusReport report = lastReport;
            String pkg = report == null ? null : report.foregroundPackage;
            if (pkg == null || pkg.length() == 0) {
                // 现场再拿一次状态（可能会触发一次查询）
                ModuleBroadcasts.requestStatus(this);
                Toast.makeText(this, R.string.preset_needs_foreground, Toast.LENGTH_LONG).show();
                return;
            }
            com.edgeguard.gesture.common.Rule existing = store.getPackageRule(pkg);
            boolean left = existing != null && existing.blockLeft;
            boolean right = existing != null && existing.blockRight;
            RuleSet next = store.setPackageRule(pkg, left, right, true, true);
            ModuleBroadcasts.pushRules(this, next);
            adapter.notifyDataSetChanged();
            Toast.makeText(this, getString(R.string.preset_applied, pkg),
                    Toast.LENGTH_LONG).show();
            Logx.i("game preset applied to " + pkg + " (top+bottom)");
        } catch (Throwable t) {
            Logx.w("applyGamePreset failed", t);
            Toast.makeText(this, "预设失败：" + t.getClass().getSimpleName(),
                    Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 阈值 + 验证模式 + 策略 B 开关（全部是模块级配置，随规则表下发）。
     *
     * 为什么阈值要在这里暴露：不同机型的导航条/状态栏高度不同，
     * 12% 只是保守默认值；音游玩家可以据此把屏蔽区调窄到刚好覆盖系统手势区。
     */
    private void showTuningDialog() {
        try {
            final RuleSet current = store.load();
            ModuleConfig cfg = current.config();

            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            int pad = (int) (16 * getResources().getDisplayMetrics().density);
            root.setPadding(pad, pad, pad, pad);

            final TextView hint = new TextView(this);
            hint.setText(R.string.tuning_hint);
            hint.setTextSize(12f);
            root.addView(hint);

            final TextView topLabel = new TextView(this);
            final SeekBar topBar = new SeekBar(this);
            topBar.setMax(28);   // 2% ~ 30%
            topBar.setProgress(Math.round(cfg.topZoneRatio * 100f) - 2);
            topLabel.setText(getString(R.string.tuning_top) + "：" + Math.round(cfg.topZoneRatio * 100f) + "%");
            root.addView(topLabel);
            root.addView(topBar);

            final TextView bottomLabel = new TextView(this);
            final SeekBar bottomBar = new SeekBar(this);
            bottomBar.setMax(28);
            bottomBar.setProgress(Math.round(cfg.bottomZoneRatio * 100f) - 2);
            bottomLabel.setText(getString(R.string.tuning_bottom) + "：" + Math.round(cfg.bottomZoneRatio * 100f) + "%");
            root.addView(bottomLabel);
            root.addView(bottomBar);

            SeekBar.OnSeekBarChangeListener listener = new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (seekBar == topBar) {
                        topLabel.setText(getString(R.string.tuning_top) + "：" + (progress + 2) + "%");
                    } else {
                        bottomLabel.setText(getString(R.string.tuning_bottom) + "：" + (progress + 2) + "%");
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                }
            };
            topBar.setOnSeekBarChangeListener(listener);
            bottomBar.setOnSeekBarChangeListener(listener);

            final CheckBox verify = new CheckBox(this);
            verify.setText(R.string.tuning_verify);
            verify.setTextSize(13f);
            verify.setChecked(cfg.verifyOnly);
            root.addView(verify);

            final CheckBox launcher = new CheckBox(this);
            launcher.setText(R.string.tuning_launcher);
            launcher.setTextSize(13f);
            launcher.setChecked(cfg.launcherBottom);
            root.addView(launcher);

            new android.app.AlertDialog.Builder(this)
                    .setTitle(R.string.tuning_title)
                    .setView(root)
                    .setPositiveButton(android.R.string.ok,
                            new android.content.DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(android.content.DialogInterface dialog, int which) {
                                    saveTuning(topBar.getProgress() + 2,
                                            bottomBar.getProgress() + 2,
                                            verify.isChecked(), launcher.isChecked(), current);
                                }
                            })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        } catch (Throwable t) {
            Logx.w("showTuningDialog failed", t);
            Toast.makeText(this, "打开设置失败：" + t.getClass().getSimpleName(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void saveTuning(int topPercent, int bottomPercent, boolean verifyOnly,
                            boolean launcherBottom, RuleSet current) {
        try {
            ModuleConfig cfg = (current == null ? ModuleConfig.DEFAULT : current.config())
                    .withTopRatio(topPercent / 100f)
                    .withBottomRatio(bottomPercent / 100f)
                    .withVerifyOnly(verifyOnly)
                    .withLauncherBottom(launcherBottom);
            RuleSet next = store.setConfig(cfg);
            ModuleBroadcasts.pushRules(this, next);
            Toast.makeText(this, R.string.tuning_saved, Toast.LENGTH_SHORT).show();
            Logx.i("tuning saved: " + cfg.describe());
        } catch (Throwable t) {
            Logx.w("saveTuning failed", t);
        }
    }

    private void openAccessibilitySettings() {
        Toast.makeText(this, R.string.accessibility_toast, Toast.LENGTH_LONG).show();
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Throwable t) {
            Logx.w("open accessibility settings failed", t);
        }
    }
}
