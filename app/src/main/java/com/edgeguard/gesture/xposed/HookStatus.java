package com.edgeguard.gesture.xposed;

import com.edgeguard.gesture.common.Logx;
import com.edgeguard.gesture.common.StatusReport;

/** 组装状态报告（App 的 UI 直接展示）。所有异常都被吞掉，只返回尽量完整的信息。 */
final class HookStatus {

    private HookStatus() {
    }

    static String buildJson() {
        try {
            StatusReport st = HookRuntime.status;
            ForegroundTracker.Fg fg = ForegroundTracker.get();
            if (fg != null) {
                st.foregroundPackage = fg.packageName;
                st.foregroundActivity = fg.activityName == null ? "" : fg.activityName;
                st.foregroundStrategy = fg.strategy;
            }
            st.ruleCount = HookRuntime.rules == null ? 0 : HookRuntime.rules.size();
            // 底部"策略 A"的实测计数：只有 seen>0 才能说这条通道看得到底部触摸。
            // 这不是猜测，是输入通道真实收到的事件数。
            st.bottomMeasure = "seen=" + HookRuntime.bottomZoneSeen
                    + " blocked=" + HookRuntime.bottomZoneBlocked;
            st.zoneConfig = Zones.describe(HookRuntime.config, Zones.screenHeight());
            st.verifyOnly = HookRuntime.verifyMode();
            st.armed = ArmController.isArmed();
            st.armedSource = ArmController.source();
            st.launcherState = "策略B("
                    + (HookRuntime.config.launcherBottom ? "已开" : "关闭")
                    + ")：需手工扩展 Launcher 作用域，结果见 logcat [LAUNCHER]";
            st.timestamp = System.currentTimeMillis();
            return st.toJson();
        } catch (Throwable t) {
            Logx.w("buildJson failed", t);
            return "{}";
        }
    }
}
