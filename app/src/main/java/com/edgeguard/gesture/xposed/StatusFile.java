package com.edgeguard.gesture.xposed;

import android.os.Environment;

import com.edgeguard.gesture.common.ModuleContract;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 把 Hook 进程的执行轨迹写到「模块 App 的外部私有目录」：
 *   /sdcard/Android/data/com.edgeguard.gesture/files/hook-status.txt
 *
 * 为什么用这条通道：
 *  - Settings.Global 在某些 ROM 上会被包名/uid 校验拒绝；
 *  - 广播可能被限制；
 *  - 而 SystemUI 持有 MANAGE_EXTERNAL_STORAGE，可以写别的应用的 Android/data 目录，
 *    模块 App 读自己的外部目录又完全不需要权限 —— 于是 App 能直接显示 Hook 走到哪一步。
 */
final class StatusFile {

    private static final String TAIL = "/Android/data/" + ModuleContract.MODULE_PACKAGE + "/files";
    private static final String NAME = "hook-status.txt";
    private static final long MAX_BYTES = 16 * 1024;

    private StatusFile() {
    }

    static void append(String line) {
        try {
            File dir = new File(Environment.getExternalStorageDirectory(), TAIL);
            if (!dir.exists() && !dir.mkdirs()) {
                return;
            }
            File file = new File(dir, NAME);
            if (file.exists() && file.length() > MAX_BYTES) {
                // 太长就清空重来，避免无限增长
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
            FileOutputStream out = new FileOutputStream(file, true);
            try {
                String text = System.currentTimeMillis() + " " + line + "\n";
                out.write(text.getBytes("UTF-8"));
                out.flush();
            } finally {
                out.close();
            }
        } catch (Throwable ignored) {
            // 写失败不影响任何手势行为
        }
    }
}
