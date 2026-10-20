package com.edgeguard.gesture.xposed;

import com.edgeguard.gesture.common.Logx;

import de.robv.android.xposed.XposedBridge;

/**
 * 关键步骤日志：同时写 logcat 和 LSPosed 的 modules.log。
 *
 * 为什么需要：LSPosed 导出的 full.log 有时不含目标进程的 logcat 行，
 * 但 modules.log 一定会带上模块 tag，因此把每一步都写进去，才能远程定位卡在哪。
 */
final class Xlog {

    private Xlog() {
    }

    static void step(String msg) {
        Logx.i(msg);
        try {
            XposedBridge.log("[EdgeGestureGuard] " + msg);
        } catch (Throwable ignored) {
        }
        // 第三条通道：App 自己的外部目录，用户不用导日志也能看到轨迹
        StatusFile.append(msg);
    }
}
