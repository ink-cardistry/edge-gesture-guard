package com.edgeguard.gesture.common;

import android.content.Context;
import android.content.Intent;

/**
 * 三个方向的 IPC 发送封装。所有发送都在 try/catch 内，失败只记日志。
 */
public final class ModuleBroadcasts {

    private ModuleBroadcasts() {
    }

    /** App -> SystemUI：全量下发规则 */
    public static void pushRules(Context context, RuleSet set) {
        pushRules(context, set == null ? "" : set.toJson());
    }

    public static void pushRules(Context context, String json) {
        try {
            Intent intent = new Intent(ModuleContract.ACTION_RULES_PUSH);
            intent.setPackage(ModuleContract.SYSTEMUI_PACKAGE);
            intent.putExtra(ModuleContract.EXTRA_RULES_JSON, json == null ? "" : json);
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES | Intent.FLAG_RECEIVER_FOREGROUND);
            context.sendBroadcast(intent);
            Logx.i("rules pushed to SystemUI, " + (json == null ? 0 : json.length()) + " chars");
            // 策略 B：Launcher 进程里如果没有规则，LauncherBottomHooks 就永远放行。
            // 这里顺手给桌面包也推一份（没装/没扩展作用域时系统只是丢弃，无副作用）。
            for (String launcher : ModuleContract.LAUNCHER_PACKAGES) {
                try {
                    Intent copy = new Intent(ModuleContract.ACTION_RULES_PUSH);
                    copy.setPackage(launcher);
                    copy.putExtra(ModuleContract.EXTRA_RULES_JSON, json == null ? "" : json);
                    copy.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES
                            | Intent.FLAG_RECEIVER_FOREGROUND);
                    context.sendBroadcast(copy);
                } catch (Throwable ignored) {
                    // 单个包失败不影响 SystemUI 的主通道
                }
            }
        } catch (Throwable t) {
            Logx.w("pushRules failed", t);
        }
    }

    /** App -> SystemUI：前台应用（无障碍助手） */
    public static void pushForeground(Context context, String packageName, String activityName) {
        try {
            if (packageName == null || packageName.length() == 0) {
                return;
            }
            Intent intent = new Intent(ModuleContract.ACTION_FOREGROUND_PUSH);
            intent.setPackage(ModuleContract.SYSTEMUI_PACKAGE);
            intent.putExtra(ModuleContract.EXTRA_PACKAGE, packageName);
            intent.putExtra(ModuleContract.EXTRA_ACTIVITY, activityName == null ? "" : activityName);
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            context.sendBroadcast(intent);
        } catch (Throwable t) {
            Logx.w("pushForeground failed", t);
        }
    }

    /**
     * App -> SystemUI：进入 / 退出「游戏模式」。
     *
     * 只发命令、不假设结果：真正的状态以 SystemUI 回推的 StatusReport.armed 为准
     * （所以 App 的磁贴/通知在收到回推后会再刷新一次）。
     */
    public static void sendArmCommand(Context context, boolean arm) {
        try {
            Intent intent = new Intent(arm
                    ? ModuleContract.ACTION_ARM : ModuleContract.ACTION_DISARM);
            intent.setPackage(ModuleContract.SYSTEMUI_PACKAGE);
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES | Intent.FLAG_RECEIVER_FOREGROUND);
            context.sendBroadcast(intent);
            Logx.i("arm command sent: " + (arm ? "ARM" : "DISARM"));
        } catch (Throwable t) {
            Logx.w("sendArmCommand failed", t);
        }
    }

    /** App -> SystemUI：请求一份状态报告 */
    public static void requestStatus(Context context) {
        try {
            Intent intent = new Intent(ModuleContract.ACTION_STATUS_REQUEST);
            intent.setPackage(ModuleContract.SYSTEMUI_PACKAGE);
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES | Intent.FLAG_RECEIVER_FOREGROUND);
            context.sendBroadcast(intent);
        } catch (Throwable t) {
            Logx.w("requestStatus failed", t);
        }
    }

    /** SystemUI -> App：请求重新推送规则（SystemUI 刚启动/重启） */
    public static void requestRulesFromApp(Context context) {
        try {
            Intent intent = new Intent(ModuleContract.ACTION_RULES_REQUEST);
            intent.setPackage(ModuleContract.MODULE_PACKAGE);
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES | Intent.FLAG_RECEIVER_FOREGROUND);
            context.sendBroadcast(intent);
            Logx.i("asked module app to push rules");
        } catch (Throwable t) {
            Logx.w("requestRulesFromApp failed", t);
        }
    }

    /** SystemUI -> App：状态报告 */
    public static void pushStatus(Context context, String statusJson) {
        try {
            Intent intent = new Intent(ModuleContract.ACTION_STATUS_PUSH);
            intent.setPackage(ModuleContract.MODULE_PACKAGE);
            intent.putExtra(ModuleContract.EXTRA_STATUS_JSON, statusJson == null ? "" : statusJson);
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            context.sendBroadcast(intent);
        } catch (Throwable t) {
            Logx.w("pushStatus failed", t);
        }
    }
}
