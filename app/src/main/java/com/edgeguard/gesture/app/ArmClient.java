package com.edgeguard.gesture.app;

import android.content.Context;
import android.content.SharedPreferences;

import com.edgeguard.gesture.common.Logx;
import com.edgeguard.gesture.common.ModuleBroadcasts;
import com.edgeguard.gesture.common.ModuleContract;
import com.edgeguard.gesture.common.StatusReport;

/**
 * App 侧的"游戏模式遥控器"。
 *
 * 重要分工：**权威状态在 SystemUI**（{@code ArmController}）。这里只做三件事：
 *   1) 发 ARM / DISARM 命令；
 *   2) 缓存"最近一次已知状态"（给磁贴/通知/UI 显示）；
 *   3) 收到 SystemUI 回推后同步 UI 与通知。
 *
 * 这样即使本 App 被冻结/杀死，SystemUI 仍然能在"锁屏→解锁"时自己退出游戏模式。
 */
public final class ArmClient {

    private ArmClient() {
    }

    private static SharedPreferences prefs(Context context) {
        Context app = context.getApplicationContext();
        if (app == null) {
            app = context;
        }
        return app.getSharedPreferences(ModuleContract.PREFS_RULES, Context.MODE_PRIVATE);
    }

    /** 最近一次已知状态（不是权威值）。 */
    public static boolean isArmed(Context context) {
        try {
            return prefs(context).getBoolean(ModuleContract.PREF_ARMED, false);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 请求进入 / 退出游戏模式。 */
    public static void setArmed(Context context, boolean armed, String source) {
        try {
            prefs(context).edit()
                    .putBoolean(ModuleContract.PREF_ARMED, armed)
                    .putString(ModuleContract.PREF_ARMED_SOURCE, source == null ? "" : source)
                    .apply();
            // 先按"请求值"更新本地显示（回推到达后会再对齐一次）
            ArmNotifier.refresh(context, armed);
            ModuleBroadcasts.sendArmCommand(context, armed);
            ModuleBroadcasts.requestStatus(context);
            Logx.i("arm requested: " + armed + " via " + source);
        } catch (Throwable t) {
            Logx.w("setArmed failed", t);
        }
    }

    /** 收到 SystemUI 的状态回推：以它为准同步一切。 */
    public static void syncFromStatus(Context context, StatusReport report) {
        if (context == null || report == null) {
            return;
        }
        try {
            boolean local = isArmed(context);
            if (local != report.armed) {
                Logx.i("armed state synced from SystemUI: " + local + " -> " + report.armed);
            }
            prefs(context).edit()
                    .putBoolean(ModuleContract.PREF_ARMED, report.armed)
                    .putString(ModuleContract.PREF_ARMED_SOURCE,
                            report.armedSource == null ? "" : report.armedSource)
                    .apply();
            ArmNotifier.refresh(context, report.armed);
            GameTileService.requestRefresh(context);
        } catch (Throwable t) {
            Logx.w("syncFromStatus failed", t);
        }
    }

    /**
     * 开机自检：会话不跨重启（重启后 SystemUI 侧本来就复位了，
     * 这里把 App 的缓存与通知一起清掉，避免磁贴显示成"已开"但实际没拦）。
     */
    public static void clearOnBoot(Context context) {
        try {
            if (!isArmed(context)) {
                ArmNotifier.cancel(context);
                return;
            }
            prefs(context).edit()
                    .putBoolean(ModuleContract.PREF_ARMED, false)
                    .putString(ModuleContract.PREF_ARMED_SOURCE, "")
                    .apply();
            ArmNotifier.cancel(context);
            ModuleBroadcasts.sendArmCommand(context, false);
            Logx.i("armed cleared on boot");
        } catch (Throwable t) {
            Logx.w("clearOnBoot failed", t);
        }
    }
}
