package com.edgeguard.gesture.xposed;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

import com.edgeguard.gesture.common.Logx;

/**
 * 「游戏模式」会话总闸（运行在 SystemUI 进程里，**权威状态在这里**）。
 *
 * 为什么放在 SystemUI：
 *  - SystemUI 常驻，锁屏/解锁广播一定能收到；App 进程随时可能被冻结或杀死。
 *    如果把总闸放在 App 的 SharedPreferences，会出现"已经锁屏解锁了但 SystemUI 还在拦"的鬼状态。
 *  - 触摸门禁也在这里，读一个 volatile 布尔即可，零 IPC、零 IO。
 *
 * 语义（与用户的交互约定一致）：
 *  - 开启：QS 磁贴 / 常驻通知按钮 / 控制器按钮 → 广播 ACTION_ARM → {@link #arm(String)}
 *  - 退出：① 锁屏后解锁（SCREEN_OFF 置脏，USER_PRESENT 自动退出）——这是主出口；
 *          ② 通知/磁贴/控制器按钮 → ACTION_DISARM；
 *          ③ SystemUI 重启（本类状态天然复位）。
 *
 * 为什么主出口是"锁屏解锁"：游戏模式会屏蔽顶部下拉，通知栏打不开 →
 * 磁贴/通知按钮都可能够不着。锁屏是唯一 100% 可达的操作，所以用它当保险丝。
 */
final class ArmController {

    private static volatile boolean armed;
    /** 本次会话期间是否发生过 SCREEN_OFF（需要它才能判定"锁屏后又解锁了"） */
    private static volatile boolean sawScreenOff;
    private static volatile String source = "";
    private static volatile boolean watching;

    private ArmController() {
    }

    static boolean isArmed() {
        return armed;
    }

    static String source() {
        return source;
    }

    static void arm(String from) {
        try {
            armed = true;
            sawScreenOff = false;
            source = from == null ? "" : from;
            Xlog.step("GAME MODE ON (via " + source + ")");
            pushStatus();
        } catch (Throwable t) {
            Logx.w("arm failed", t);
        }
    }

    static void disarm(String reason) {
        try {
            boolean was = armed;
            armed = false;
            source = "";
            if (was) {
                Xlog.step("GAME MODE OFF (" + reason + ")");
            }
            pushStatus();
        } catch (Throwable t) {
            Logx.w("disarm failed", t);
        }
    }

    private static void pushStatus() {
        try {
            Context context = HookRuntime.anyContext();
            if (context != null) {
                RulesPipe.sendStatus(context);
            }
        } catch (Throwable t) {
            Logx.w("pushStatus after arm change failed", t);
        }
    }

    /** 锁屏/解锁监听（只在 SystemUI 进程内注册一次）。 */
    static void startLockWatch(Context context) {
        if (context == null || watching) {
            return;
        }
        synchronized (ArmController.class) {
            if (watching) {
                return;
            }
            watching = true;
        }
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_USER_PRESENT);
            if (Build.VERSION.SDK_INT >= 33) {
                // 系统广播来自 system uid，NOT_EXPORTED 仍然收得到；更安全
                context.registerReceiver(RECEIVER, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(RECEIVER, filter);
            }
            Xlog.step("game-mode lock watch armed (SCREEN_OFF/USER_PRESENT)");
        } catch (Throwable t) {
            Xlog.step("lock watch register failed: " + t);
        }
    }

    private static final BroadcastReceiver RECEIVER = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                if (intent == null || intent.getAction() == null) {
                    return;
                }
                String action = intent.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    if (armed) {
                        sawScreenOff = true;
                        Xlog.debug("GAME MODE: screen off -> armed for exit-on-unlock");
                    }
                } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
                    if (armed && sawScreenOff) {
                        disarm("auto: lock -> unlock");
                    }
                }
                // ACTION_SCREEN_ON 不处理：真正的"解锁完成"是 USER_PRESENT
            } catch (Throwable t) {
                Logx.w("lock watch receiver failed", t);
            }
        }
    };
}
