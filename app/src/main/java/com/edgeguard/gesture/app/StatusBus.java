package com.edgeguard.gesture.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.edgeguard.gesture.common.Logx;
import com.edgeguard.gesture.common.ModuleContract;
import com.edgeguard.gesture.common.StatusReport;

/** 状态报告 -> UI 的分发（主线程回调）+ 最近一次报告持久化。 */
public final class StatusBus {

    public interface Listener {
        void onStatus(StatusReport report);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile Listener listener;

    private StatusBus() {
    }

    public static void setListener(Listener newListener) {
        listener = newListener;
    }

    public static void publish(Context context, String json) {
        if (json == null) {
            return;
        }
        try {
            Context app = context.getApplicationContext();
            if (app == null) {
                app = context;
            }
            app.getSharedPreferences(ModuleContract.PREFS_RULES, Context.MODE_PRIVATE)
                    .edit()
                    .putString(ModuleContract.PREF_STATUS_JSON, json)
                    .apply();
        } catch (Throwable t) {
            Logx.w("save status failed", t);
        }
        final StatusReport report = StatusReport.fromJson(json);
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                Listener l = listener;
                if (l != null) {
                    try {
                        l.onStatus(report);
                    } catch (Throwable t) {
                        Logx.w("status listener failed", t);
                    }
                }
            }
        });
    }

    public static StatusReport last(Context context) {
        try {
            Context app = context.getApplicationContext();
            if (app == null) {
                app = context;
            }
            String json = app.getSharedPreferences(ModuleContract.PREFS_RULES, Context.MODE_PRIVATE)
                    .getString(ModuleContract.PREF_STATUS_JSON, null);
            if (json != null && json.length() > 0) {
                return StatusReport.fromJson(json);
            }
        } catch (Throwable t) {
            Logx.w("read last status failed", t);
        }
        return null;
    }
}
