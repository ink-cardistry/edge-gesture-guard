package com.edgeguard.gesture.common;

import android.util.Log;

/**
 * 统一日志入口。
 *
 * 作用：
 *  1) 全模块共用同一个 TAG，方便用
 *     {@code adb logcat -s EdgeGestureGuard} 观察探测结果；
 *  2) 任何日志失败都不允许影响手势逻辑，所以全部 try/catch。
 */
public final class Logx {

    public static final String TAG = "EdgeGestureGuard";

    private Logx() {
    }

    public static void i(String msg) {
        print(Log.INFO, msg, null);
    }

    public static void w(String msg) {
        print(Log.WARN, msg, null);
    }

    public static void w(String msg, Throwable t) {
        print(Log.WARN, msg, t);
    }

    public static void e(String msg) {
        print(Log.ERROR, msg, null);
    }

    public static void e(String msg, Throwable t) {
        print(Log.ERROR, msg, t);
    }

    private static void print(int priority, String msg, Throwable t) {
        try {
            if (t == null) {
                Log.println(priority, TAG, String.valueOf(msg));
            } else {
                Log.println(priority, TAG, String.valueOf(msg) + " :: " + Log.getStackTraceString(t));
            }
        } catch (Throwable ignored) {
            // 日志永远不能成为崩溃源
        }
    }
}
