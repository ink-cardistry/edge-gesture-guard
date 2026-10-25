package com.edgeguard.gesture.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.edgeguard.gesture.common.Logx;
import com.edgeguard.gesture.common.ModuleContract;

/**
 * 常驻通知按钮的落点（静态接收器，App 进程被回收也能被拉起）。
 * 只做一件事：把请求转成 ARM / DISARM 命令，真实状态由 SystemUI 回推。
 */
public final class ArmedReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            if (context == null || intent == null || intent.getAction() == null) {
                return;
            }
            String action = intent.getAction();
            if (ModuleContract.ACTION_NOTIFY_EXIT.equals(action)) {
                ArmClient.setArmed(context, false, "notification");
            } else if (ModuleContract.ACTION_NOTIFY_TOGGLE.equals(action)) {
                ArmClient.setArmed(context, !ArmClient.isArmed(context), "notification");
            }
        } catch (Throwable t) {
            Logx.w("ArmedReceiver failed", t);
        }
    }
}
