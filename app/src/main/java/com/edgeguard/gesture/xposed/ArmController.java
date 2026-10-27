package com.edgeguard.gesture.xposed;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import com.edgeguard.gesture.common.Logx;
import com.edgeguard.gesture.common.ModuleConfig;

/**
 * 「游戏模式」会话总闸（运行在 SystemUI 进程里，**权威状态在这里**）。
 *
 * 为什么放在 SystemUI：它常驻，能收到锁屏/解锁广播；App 进程随时可能被冻结或杀死。
 * 触摸门禁也在这里，读一个 volatile 布尔即可，零 IPC、零 IO。
 *
 * 退出（1.5.3 重做）：**不再依赖 ACTION_USER_PRESENT 一定送达**。
 *   1) 快路径：SCREEN_OFF / SCREEN_ON / USER_PRESENT 广播（RECEIVER_EXPORTED，
 *      与规则接收器同一个标志 —— 1.5.1 用了 NOT_EXPORTED，实测系统广播没送到，退出失效）；
 *   2) 保险丝：会话期间每 3 秒自检一次「息屏 or keyguard 仍锁着」，
 *      先记录"曾经锁过"，再在"已解锁且亮屏"时自动退出。
 *      这样即使 ROM 完全不发 USER_PRESENT，也一定能退出。
 *
 * 只在 armed 期间跑这个 3s 自检，退出即停，不会长期耗电。
 */
final class ArmController {

    /** 自检间隔（毫秒）。只在 armed 期间运行。 */
    private static final long WATCH_MS = 3000L;

    private static volatile boolean armed;
    /** 本次会话期间是否进入过"锁定/息屏"状态（退出判定必须基于它，避免误退） */
    private static volatile boolean sawLocked;
    private static volatile String source = "";
    private static volatile boolean watching;
    private static volatile Handler watchdog;

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
            sawLocked = false;
            source = from == null ? "" : from;
            Xlog.step("GAME MODE ON (via " + source + ")");
            Context context = HookRuntime.anyContext();
            if (context != null) {
                startLockWatch(context);
            }
            startWatchdog();
            try {
                StatusBarHider.enforce();
            } catch (Throwable ignored) {
            }
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
            stopWatchdog();
            try {
                StatusBarHider.restore();
            } catch (Throwable ignored) {
            }
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

    // ------------------------------------------------------------------
    // 快路径：广播
    // ------------------------------------------------------------------

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
                // 关键：与规则接收器一致用 EXPORTED。
                // 1.5.1 用 NOT_EXPORTED 注册系统广播，实测（LineageOS 24/A17）收不到，
                // 导致"锁屏后解锁不退出"。这三个都是 protected broadcast，
                // 只有系统能发，用 EXPORTED 没有安全风险。
                context.registerReceiver(RECEIVER, filter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(RECEIVER, filter);
            }
            Xlog.step("game-mode lock watch armed (SCREEN_OFF/ON/USER_PRESENT, EXPORTED)");
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
                Xlog.step("ARM-LOCK recv " + action + " armed=" + armed + " sawLocked=" + sawLocked);
                if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    // 不看 armed：先把"锁过"记下来，arm 发生在息屏之后也能正确退出
                    sawLocked = true;
                } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                    // 亮屏后给用户一点时间解锁，再确认
                    scheduleUnlockCheck(1200L);
                } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
                    if (armed && sawLocked) {
                        maybeExitOnUnlock("auto: user-present");
                    }
                }
            } catch (Throwable t) {
                Logx.w("lock watch receiver failed", t);
            }
        }
    };

    private static void scheduleUnlockCheck(long delayMs) {
        try {
            Handler h = watchdog;
            if (h == null) {
                h = new Handler(Looper.getMainLooper());
                watchdog = h;
            }
            h.postDelayed(WATCH, delayMs);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // 保险丝：armed 期间自检（不依赖任何广播）
    // ------------------------------------------------------------------

    private static void startWatchdog() {
        try {
            Handler h = watchdog;
            if (h == null) {
                h = new Handler(Looper.getMainLooper());
                watchdog = h;
            }
            h.removeCallbacks(WATCH);
            h.postDelayed(WATCH, WATCH_MS);
            Xlog.step("game-mode watchdog started (" + WATCH_MS + "ms)");
        } catch (Throwable t) {
            Logx.w("startWatchdog failed", t);
        }
    }

    private static void stopWatchdog() {
        try {
            Handler h = watchdog;
            if (h != null) {
                h.removeCallbacks(WATCH);
            }
        } catch (Throwable ignored) {
        }
    }

    private static final Runnable WATCH = new Runnable() {
        @Override
        public void run() {
            try {
                if (!armed) {
                    return;
                }
                boolean locked = isLockedNow();
                if (locked) {
                    if (!sawLocked) {
                        sawLocked = true;
                        Xlog.step("ARM-LOCK locked/screen-off detected -> will exit on unlock");
                    }
                } else if (sawLocked) {
                    maybeExitOnUnlock("auto: unlocked");
                    return;
                }

                try {
                    StatusBarHider.enforce();
                } catch (Throwable ignored) {
                }
                Handler h = watchdog;
                if (h != null && armed) {
                    h.postDelayed(this, WATCH_MS);
                }
            } catch (Throwable t) {
                Logx.w("watchdog tick failed", t);
            }
        }
    };

    /**
     * 解锁后的退出判定。
     *
     * 默认退出（与你最初的约定一致）；但如果用户显式打开了「锁屏后保持」，
     * 就只清掉 sawLocked 继续会话 —— 打音游时"锁一下屏再回来"不该丢状态。
     */
    private static void maybeExitOnUnlock(String reason) {
        ModuleConfig cfg = HookRuntime.config;
        if (cfg != null && cfg.keepOnUnlock) {
            sawLocked = false;
            Xlog.step("GAME MODE kept after unlock (keepOnUnlock=true)");
            return;
        }
        disarm(reason);
    }

    /** 当前是否处于"锁着"状态：息屏算锁，keyguard 未消失也算锁。拿不到状态时按"仍锁着"处理（不误退）。 */
    private static boolean isLockedNow() {
        Context context = HookRuntime.anyContext();
        if (context == null) {
            return true;
        }
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isInteractive()) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            KeyguardManager km =
                    (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
            return km != null && km.isKeyguardLocked();
        } catch (Throwable t) {
            return true;
        }
    }
}
