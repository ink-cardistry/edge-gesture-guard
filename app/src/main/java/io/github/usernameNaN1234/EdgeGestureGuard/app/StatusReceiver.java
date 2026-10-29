package io.github.usernameNaN1234.EdgeGestureGuard.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import io.github.usernameNaN1234.EdgeGestureGuard.common.Logx;
import io.github.usernameNaN1234.EdgeGestureGuard.common.ModuleContract;

/** 接收 SystemUI 里的 Hook 发回的状态报告。 */
public final class StatusReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            if (context == null || intent == null) {
                return;
            }
            if (!ModuleContract.ACTION_STATUS_PUSH.equals(intent.getAction())) {
                return;
            }
            String json = intent.getStringExtra(ModuleContract.EXTRA_STATUS_JSON);
            if (json == null || json.length() == 0) {
                return;
            }
            StatusBus.publish(context, json);
            io.github.usernameNaN1234.EdgeGestureGuard.common.StatusReport report =
                    io.github.usernameNaN1234.EdgeGestureGuard.common.StatusReport.fromJson(json);
            // SystemUI 是权威：把 armed 写回本地，并刷新常驻通知/磁贴显示
            ArmClient.syncFromStatus(context, report);
            // 顺带把诊断（状态 + Hook 日志尾部）落盘，用户不需要从 logcat 复制
            DiagExporter.exportAsync(context, report, "status-push");
        } catch (Throwable t) {
            Logx.w("StatusReceiver failed", t);
        }
    }
}
