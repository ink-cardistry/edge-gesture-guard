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
            st.timestamp = System.currentTimeMillis();
            return st.toJson();
        } catch (Throwable t) {
            Logx.w("buildJson failed", t);
            return "{}";
        }
    }
}
