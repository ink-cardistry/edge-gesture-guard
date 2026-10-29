package io.github.usernameNaN1234.EdgeGestureGuard.xposed;

import android.content.Context;

import io.github.usernameNaN1234.EdgeGestureGuard.common.Logx;
import io.github.usernameNaN1234.EdgeGestureGuard.common.ModuleConfig;
import io.github.usernameNaN1234.EdgeGestureGuard.common.RuleSet;
import io.github.usernameNaN1234.EdgeGestureGuard.common.StatusReport;

/**
 * Hook 进程内的共享状态（SystemUI 进程，以及可选的 Launcher 进程各有一份）。
 *
 * rules / config 都是 volatile 的不可变整表引用：触摸回调里只做一次引用读取 + map 查询，
 * 没有锁、没有 IO，不会拖慢手势。
 */
final class HookRuntime {

    static volatile Context appContext;

    /**
     * 兜底 Context：Application 还没创建时（实测 appContext=null）用它来
     * 查 ContentResolver / 发广播 / 写缓存。SystemContext 就够用。
     */
    static volatile Context pipeContext;

    /** 调试开关：true 时才会打印每事件日志（默认关闭，避免 IO 与日志刷屏）。 */
    static final boolean DEBUG = false;

    static volatile RuleSet rules = RuleSet.EMPTY;

    /** 全局几何/调试配置（顶部/底部比例、验证模式、Launcher 开关）。 */
    static volatile ModuleConfig config = ModuleConfig.DEFAULT;

    /**
     * 是否需要"按事件观测区域"。
     *
     * 只有存在底部/顶部规则、或打开了验证模式时才为 true ——
     * 否则热路径连 rawY 都不读，等同于模块没安装。
     */
    static volatile boolean observeZones;

    static final StatusReport status = new StatusReport();

    /** 已挂载的入口数量（写进自检记录，App 侧可读）。 */
    static volatile int legacyHooks;
    static volatile int modernHooks;

    // ---- 观测计数（输入线程写，状态上报线程读；只用于诊断，不需要精确）----
    /** 在"返回手势输入通道"上观测到的底部区域 DOWN 事件数 */
    static volatile int bottomZoneSeen;
    /** 其中真正被吞掉的 */
    static volatile int bottomZoneBlocked;
    /** 在任意 Hook 上观测到的顶部区域 DOWN 事件数 */
    static volatile int topZoneSeen;
    /** 通知栏下拉被拦截的次数 */
    static volatile int shadeBlocked;
    /** 通知栏 Hook 收到的事件数 */
    static volatile int shadeSeen;
    /** 导航栏 Hook 挂载数 / 捕获到的导航栏视图数（写进状态报告，不会被日志环形缓冲冲掉） */
    static volatile int navHooks;
    static volatile int navBars;
    /** 导航栏诊断摘要（类名/方法数/视图树），写进状态报告，不会被日志环形缓冲冲掉 */
    static volatile String navDetail = "";

    /** 诊断环形缓冲（只在内存里，随状态报告送到 App）。最多 300 行。 */
    private static final int DIAG_MAX = 300;
    private static final java.util.ArrayDeque<String> DIAG = new java.util.ArrayDeque<>();

    private HookRuntime() {
    }

    /** 把日志旁路接到环形缓冲（Hook 进程启动时装一次）。 */
    static void installLogSink() {
        try {
            int max = DIAG_MAX;
            Logx.sink = new Logx.Sink() {
                @Override
                public void onLog(int priority, String message) {
                    try {
                        if (message == null) {
                            return;
                        }
                        String line = android.os.SystemClock.elapsedRealtime() + " " + message;
                        if (line.length() > 320) {
                            line = line.substring(0, 320) + "…";
                        }
                        synchronized (DIAG) {
                            DIAG.addLast(line);
                            while (DIAG.size() > max) {
                                DIAG.removeFirst();
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            };
            Xlog.step("diag ring installed (max=" + DIAG_MAX + ")");
        } catch (Throwable t) {
            Logx.w("installLogSink failed", t);
        }
    }

    /** 取一份快照（按时间顺序，最多 limit 行）。 */
    static String[] diagSnapshot(int limit) {
        try {
            synchronized (DIAG) {
                java.util.ArrayList<String> all = new java.util.ArrayList<>(DIAG);
                int from = Math.max(0, all.size() - Math.max(0, limit));
                return all.subList(from, all.size()).toArray(new String[0]);
            }
        } catch (Throwable t) {
            return new String[0];
        }
    }

    /** 有真实 App context 就用它，否则用兜底 context。 */
    static Context anyContext() {
        Context context = appContext;
        if (context != null) {
            return context;
        }
        return pipeContext;
    }

    /** 规则表或配置变化后重算派生状态（App 进程每次推送都会调用，代价可忽略）。 */
    static void refreshDerived() {
        try {
            RuleSet set = rules;
            ModuleConfig cfg = config;
            boolean observe = cfg != null && cfg.verifyOnly;
            if (!observe && set != null) {
                for (io.github.usernameNaN1234.EdgeGestureGuard.common.Rule r : set.all()) {
                    if (r.blockBottom || r.blockTopShade) {
                        observe = true;
                        break;
                    }
                }
            }
            observeZones = observe;
        } catch (Throwable t) {
            Logx.w("refreshDerived failed", t);
        }
    }

    static boolean verifyMode() {
        ModuleConfig cfg = config;
        return cfg != null && cfg.verifyOnly;
    }

    static void noteForeground(String strategy, String error) {
        try {
            if (strategy != null && strategy.length() > 0) {
                status.foregroundStrategy = strategy;
            }
            if (error != null && error.length() > 0) {
                status.foregroundError = error;
            }
        } catch (Throwable ignored) {
        }
    }
}
