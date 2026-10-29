package io.github.usernameNaN1234.EdgeGestureGuard.common;

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

    /**
     * 日志旁路：Hook 进程（SystemUI）注册一个 sink，把日志收进内存环形缓冲，
     * 随状态报告一起送到 App，再由 App 落盘导出。
     *
     * 为什么需要：用户要从 logcat 里捞行再复制过来太麻烦；
     * 而触摸热路径上绝不能用 logcat 之外的 IO，所以这里只写内存。
     * App 进程不注册 sink（保持 null），因此 UI 侧没有任何额外开销。
     */
    public interface Sink {
        void onLog(int priority, String message);
    }

    public static volatile Sink sink;

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
            Sink s = sink;
            if (s != null) {
                s.onLog(priority, String.valueOf(msg)
                        + (t == null ? "" : " :: " + t.getClass().getSimpleName()
                        + ": " + t.getMessage()));
            }
        } catch (Throwable ignored) {
            // 旁路失败绝不能影响日志本身
        }
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
