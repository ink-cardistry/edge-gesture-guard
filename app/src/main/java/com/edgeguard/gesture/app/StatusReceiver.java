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
        } catch (Throwable t) {
            Logx.w("StatusReceiver failed", t);
        }
    }
}
