package com.edgeguard.gesture.xposed;

import android.content.Context;
import android.database.Cursor;
import android.os.Handler;
import android.os.Looper;

import com.edgeguard.gesture.common.Logx;
import com.edgeguard.gesture.common.ModuleContract;

/**
 * 兜底规则通道（省电版：**没有周期轮询**）。
 *
 * 只在一种情况下动作：SystemUI 启动 20 秒后仍未收到任何规则广播
 * （说明这台 ROM 上 App -> SystemUI 的广播不通），此时**拉取一次** provider，
 * 成功即结束；失败再重试一次（60s 后），然后彻底停止。
 *
 * 旧版是 15s 一次的无限轮询 —— 那是常驻唤醒源，已移除。
 */
final class ProviderPoller implements Runnable {

    private static final long FIRST_DELAY_MS = 20_000L;
    private static final long RETRY_DELAY_MS = 60_000L;

    private static volatile boolean started;
    private static Handler handler;

    private final Context context;
    private final int attempt;

    private ProviderPoller(Context context, int attempt) {
        this.context = context;
        this.attempt = attempt;
    }

    static void start(Context context) {
        if (context == null || started) {
            return;
        }
        started = true;
        try {
            handler = new Handler(Looper.getMainLooper());
            handler.postDelayed(new ProviderPoller(context, 0), FIRST_DELAY_MS);
        } catch (Throwable t) {
            Logx.w("poller start failed", t);
        }
    }

    @Override
    public void run() {
        try {
            // 已经收到过广播说明通道是通的，什么都不用做
            if (RulesPipe.lastBroadcastAt != 0L) {
                Logx.i("provider pull skipped: broadcast channel alive");
                return;
            }
            if (RulesPipe.pullFromProvider(context)) {
                Logx.i("provider pull ok");
                return;
            }
            if (attempt == 0 && handler != null) {
                handler.postDelayed(new ProviderPoller(context, 1), RETRY_DELAY_MS);
            } else {
                Logx.w("provider pull gave up (广播不通且 provider 不可用)");
            }
        } catch (Throwable t) {
            Logx.w("provider pull failed", t);
        }
    }

}