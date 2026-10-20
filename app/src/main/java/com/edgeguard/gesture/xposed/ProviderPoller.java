package com.edgeguard.gesture.xposed;

import android.content.Context;
import android.database.Cursor;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import com.edgeguard.gesture.common.Logx;
import com.edgeguard.gesture.common.ModuleContract;

/**
 * 兜底规则通道：从模块 App 的 ContentProvider 主动拉规则。
 *
 * 只在一种情况下工作：**SystemUI 启动后一段时间内一次广播都没收到**
 * （说明 App -> SystemUI 的广播在这台 ROM 上不通），此时改用轮询 provider。
 * 一旦收到任意广播，轮询自动停止，不影响功耗。
 *
 * 用独立 HandlerThread，绝不占用 SystemUI 主线程/输入线程。
 */
final class ProviderPoller implements Runnable {

    private static final long FIRST_DELAY_MS = 8000L;
    private static final long INTERVAL_MS = 15000L;
    private static final long FAIL_INTERVAL_MS = 60000L;

    private static volatile boolean started;
    private static Handler handler;

    private final Context context;
    private int failures = 0;

    private ProviderPoller(Context context) {
        this.context = context;
    }

    static void start(Context context) {
        if (context == null || started) {
            return;
        }
        started = true;
        try {
            HandlerThread thread = new HandlerThread("edgeguard-rule-poll");
            thread.start();
            handler = new Handler(thread.getLooper());
            handler.postDelayed(new ProviderPoller(context.getApplicationContext() == null
                    ? context : context.getApplicationContext()), FIRST_DELAY_MS);
            Logx.i("provider poller armed (fires only if no broadcast ever arrives)");
        } catch (Throwable t) {
            Logx.w("provider poller start failed", t);
        }
    }

    private static void reschedule(Runnable runnable, long delay) {
        Handler h = handler;
        if (h != null) {
            h.postDelayed(runnable, delay);
        }
    }

    @Override
    public void run() {
        try {
            // 收到过广播说明主通道正常，彻底停止轮询
            if (RulesPipe.lastBroadcastAt > 0) {
                Logx.i("broadcast channel works, provider poller stops");
                return;
            }
            Cursor cursor = null;
            try {
                cursor = context.getContentResolver().query(
                        ModuleContract.RULES_URI, null, null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    String json = cursor.getString(0);
                    if (json != null && json.length() > 0) {
                        RulesPipe.applyRules(json, "provider-poll");
                    }
                }
                failures = 0;
            } catch (Throwable t) {
                failures++;
                if (failures == 1) {
                    Logx.w("provider poll failed（继续尝试，60s 一次）", t);
                }
            } finally {
                if (cursor != null) {
                    try {
                        cursor.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable t) {
            Logx.w("provider poller error", t);
        }
        long delay = failures > 2 ? FAIL_INTERVAL_MS : INTERVAL_MS;
        Logx.i("provider poller next in " + delay + "ms (failures=" + failures + ")");
        reschedule(this, delay);
    }
}
