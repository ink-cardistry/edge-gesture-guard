package com.edgeguard.gesture.xposed;

import android.app.TaskStackListener;

import java.lang.reflect.Method;

/**
 * 推送式前台变化监听（省电核心）。
 *
 * 在 SystemUI 进程里向 ActivityTaskManager 注册一个 TaskStackListener：
 * 任务栈一有变化（切换应用/回桌面/进多任务）系统就回调我们，
 * 我们再**异步**刷新一次前台应用缓存。
 *
 * 这样：
 *  - 平时没有任何定时器、没有轮询；
 *  - 触摸回调里**不再做 Binder 查询**（只看缓存里的一个布尔值）；
 *  - 前台不是目标应用时，手指一碰边缘就直接放行，等于模块"没启动"。
 */
final class TaskStackWatch {

    private static volatile boolean registered;

    private TaskStackWatch() {
    }

    static boolean start() {
        if (registered) {
            return true;
        }
        try {
            Class<?> atmClass = Class.forName("android.app.ActivityTaskManager");
            Method getService = atmClass.getDeclaredMethod("getService");
            getService.setAccessible(true);
            Object atm = getService.invoke(null);
            if (atm == null) {
                Xlog.step("task-stack: ActivityTaskManager 不可用");
                return false;
            }
            Method register = atm.getClass().getMethod("registerTaskStackListener",
                    Class.forName("android.app.IActivityTaskListener"));
            register.setAccessible(true);
            register.invoke(atm, new Listener());
            registered = true;
            Xlog.step("task-stack listener registered");
            return true;
        } catch (Throwable t) {
            Xlog.step("task-stack listener 注册失败（退回按需查询）: " + t);
            return false;
        }
    }

    static final class Listener extends TaskStackListener {
        @Override
        public void onTaskStackChanged() {
            try {
                // 只置脏标记 + 异步刷新，绝不在这里做重活（这是系统 Binder 线程）
                ForegroundTracker.onTaskStackChanged();
            } catch (Throwable ignored) {
            }
        }
    }
}
