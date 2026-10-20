package com.edgeguard.gesture.xposed;

import android.content.Context;

import com.edgeguard.gesture.common.RuleSet;
import com.edgeguard.gesture.common.StatusReport;

/**
 * Hook 进程内的共享状态（只在 SystemUI 进程里存在）。
 *
 * rules 是 volatile 的不可变整表引用：触摸回调里只做一次引用读取 + map 查询，
 * 没有锁、没有 IO，不会拖慢手势。
 */
final class HookRuntime {

    static volatile Context appContext;

    /**
     * 兜底 Context：Application 还没创建时（实测 appContext=null）用它来
     * 查 ContentResolver / 发广播 / 写缓存。SystemContext 就够用。
     */
    static volatile Context pipeContext;

    static volatile RuleSet rules = RuleSet.EMPTY;
    static final StatusReport status = new StatusReport();

    /** 已挂载的入口数量（写进自检记录，App 侧可读）。 */
    static volatile int legacyHooks;
    static volatile int modernHooks;

    private HookRuntime() {
    }

    /** 有真实 App context 就用它，否则用兜底 context。 */
    static Context anyContext() {
        Context context = appContext;
        if (context != null) {
            return context;
        }
        return pipeContext;
    }

    static void noteForeground(String strategy, String error) {
        try {
            if (strategy != null && strategy.length() > 0) {
                status.foregroundStrategy = strategy;
            }
            if (error != null && error.length() > 0) {
                status.foregroundError = error;
            }
        } catch (Throwable ignored) {
        }
    }
}
