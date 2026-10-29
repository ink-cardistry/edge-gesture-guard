package io.github.usernameNaN1234.EdgeGestureGuard.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import io.github.usernameNaN1234.EdgeGestureGuard.R;
import io.github.usernameNaN1234.EdgeGestureGuard.common.Logx;
import io.github.usernameNaN1234.EdgeGestureGuard.common.ModuleContract;

/**
 * "寄生通知"：游戏模式开启期间常驻的一条低调通知。
 *
 * 为什么不挂前台服务：这条通知只需要"存在 + 两个按钮"，没有任何后台工作要做；
 * 挂 FGS 会额外消耗电量与通知权限，收益为零。按钮用 PendingIntent 指向本 App 的
 * 静态接收器，**即使 App 进程已被回收，点按钮也能重新拉起并生效**。
 *
 * 注意：游戏模式会屏蔽顶部下拉 —— 通知栏可能根本拉不下来，所以这条通知主要起
 * "状态提示 + 万一通知栏已经打开时能就地退出"的作用；主出口仍是锁屏→解锁。
 */
public final class ArmNotifier {

    private ArmNotifier() {
    }

    public static void refresh(Context context, boolean armed) {
        try {
            if (context == null) {
                return;
            }
            if (!armed) {
                cancel(context);
                return;
            }
            NotificationManager nm =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }
            ensureChannel(nm);

            Intent openIntent = new Intent(context, MainActivity.class);
            openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent open = PendingIntent.getActivity(context, 1, openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            PendingIntent exit = PendingIntent.getBroadcast(context, 2,
                    new Intent(ModuleContract.ACTION_NOTIFY_EXIT).setClass(context, ArmedReceiver.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Notification.Builder builder = new Notification.Builder(
                    context, ModuleContract.CHANNEL_GAME_MODE)
                    .setSmallIcon(R.drawable.ic_game_mode)
                    .setContentTitle(context.getString(R.string.notif_title))
                    .setContentText(context.getString(R.string.notif_text))
                    .setStyle(new Notification.BigTextStyle().bigText(
                            context.getString(R.string.notif_text_long)))
                    .setOngoing(true)
                    .setShowWhen(false)
                    .setOnlyAlertOnce(true)
                    .setCategory(Notification.CATEGORY_SERVICE)
                    .setContentIntent(open)
                    .addAction(new Notification.Action.Builder(
                            null, context.getString(R.string.notif_action_exit), exit).build());

            nm.notify(ModuleContract.NOTIF_ID_GAME_MODE, builder.build());
        } catch (Throwable t) {
            Logx.w("ArmNotifier.refresh failed", t);
        }
    }

    public static void cancel(Context context) {
        try {
            NotificationManager nm =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.cancel(ModuleContract.NOTIF_ID_GAME_MODE);
            }
        } catch (Throwable t) {
            Logx.w("ArmNotifier.cancel failed", t);
        }
    }

    private static void ensureChannel(NotificationManager nm) {
        try {
            NotificationChannel channel = new NotificationChannel(
                    ModuleContract.CHANNEL_GAME_MODE,
                    "游戏模式",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("打音游时屏蔽顶部下拉与底部上滑");
            channel.setShowBadge(false);
            channel.setSound(null, null);
            channel.enableVibration(false);
            channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
            nm.createNotificationChannel(channel);
        } catch (Throwable t) {
            Logx.w("ensureChannel failed", t);
        }
    }
}
