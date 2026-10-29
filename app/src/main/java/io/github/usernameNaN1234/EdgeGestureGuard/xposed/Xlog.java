package io.github.usernameNaN1234.EdgeGestureGuard.xposed;

import io.github.usernameNaN1234.EdgeGestureGuard.common.Logx;

import de.robv.android.xposed.XposedBridge;

/**
 * 日志分三档（省电的关键之一：热路径一律不写文件）。
 *
 *  step   —— 低频关键事件（安装 Hook、应用规则、错误），写 logcat + modules.log
 *  debug  —— 每事件级日志（边缘 DOWN、吞事件…），默认完全关闭，调试时才开
 *  marker —— 极少见的一次性标记，额外写 App 外部目录，方便用户不导日志也能看
 *
 * 旧版 step() 会顺带 StatusFile.append()，而 step() 又被每个被吞掉的事件调用，
 * 等于每次滑动都在 /sdcard 上写文件 —— 这是最主要的耗电点，已去掉。
 */
final class Xlog {

    private Xlog() {
    }

    static void step(String msg) {
        Logx.i(msg);
        bridge(msg);
    }

    static void debug(String msg) {
        if (!HookRuntime.DEBUG) {
            return;
        }
        step(msg);
    }

    static void marker(String msg) {
        step(msg);
        StatusFile.append(msg);
    }

    private static void bridge(String msg) {
        try {
            XposedBridge.log("[EdgeGestureGuard] " + msg);
        } catch (Throwable ignored) {
        }
    }
}
