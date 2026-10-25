package com.edgeguard.gesture.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.edgeguard.gesture.common.Logx;
import com.edgeguard.gesture.common.ModuleContract;

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
            // SystemUI 是权威：把 armed 写回本地，并刷新常驻通知/磁贴显示
            ArmClient.syncFromStatus(context,
                    com.edgeguard.gesture.common.StatusReport.fromJson(json));
        } catch (Throwable t) {
            Logx.w("StatusReceiver failed", t);
        }
    }
}
