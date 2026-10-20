package com.edgeguard.gesture.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.edgeguard.gesture.common.Logx;
import com.edgeguard.gesture.common.ModuleBroadcasts;
import com.edgeguard.gesture.common.ModuleContract;
import com.edgeguard.gesture.common.RuleSet;
import com.edgeguard.gesture.common.RuleStore;

/**
 * 静态接收器：负责"把规则主动推回 SystemUI"的时机
 *  - SystemUI 启动/重启后发来的 RULES_REQUEST；
 *  - 开机完成；
 *  - 模块自身被覆盖安装。
 */
public final class SyncReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            if (context == null || intent == null) {
                return;
            }
            String action = intent.getAction();
            if (action == null) {
                return;
            }
            boolean wanted = ModuleContract.ACTION_RULES_REQUEST.equals(action)
                    || Intent.ACTION_BOOT_COMPLETED.equals(action)
                    || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
            if (!wanted) {
                return;
            }
            RuleSet rules = new RuleStore(context).load();
            ModuleBroadcasts.pushRules(context, rules);
            Logx.i("SyncReceiver pushed " + rules.size() + " rules after " + action);
        } catch (Throwable t) {
            Logx.w("SyncReceiver failed", t);
        }
    }
}
