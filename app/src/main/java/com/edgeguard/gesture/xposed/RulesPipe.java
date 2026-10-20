package com.edgeguard.gesture.xposed;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.os.Build;

import com.edgeguard.gesture.common.Logx;
import com.edgeguard.gesture.common.ModuleBroadcasts;
import com.edgeguard.gesture.common.ModuleContract;
import com.edgeguard.gesture.common.RuleSet;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * Hook 进程侧的规则通道：
 *
 *  1) SystemUI 自己目录里的缓存文件（重启 SystemUI/手机后立刻恢复上次规则）；
 *  2) 动态广播接收器（App 每次改规则都会显式推过来，实时生效）；
 *  3) ContentProvider 兜底拉取（冷启动时若广播还没到）；
 *  4) 主动请求 App 重推（App 若在运行/可被广播唤醒）。
 *
 * 收发都在 try/catch 内：这条链路出问题最多是"规则不生效"，绝不会让 SystemUI 崩。
 */
final class RulesPipe {

    private static final String CACHE_DIR = "edgeguard";
    private static final String CACHE_FILE = "rules-cache.json";

    private static final Object LOCK = new Object();
    private static volatile boolean inited;

    /** 最近一次收到任何广播的时间（elapsedRealtime）；0 表示从未收到。 */
    static volatile long lastBroadcastAt;

    private RulesPipe() {
    }

    static void init(Context context) {
        if (context == null) {
            return;
        }
        synchronized (LOCK) {
            if (inited) {
                return;
            }
            inited = true;
        }
        HookRuntime.appContext = context;
        Logx.i("rules pipe init in " + safePackage(context));
        try {
            loadCache(context);
        } catch (Throwable t) {
            Logx.w("loadCache failed", t);
        }
        try {
            registerReceiver(context);
        } catch (Throwable t) {
            Logx.w("registerReceiver failed", t);
        }
        try {
            pullFromProvider(context);
        } catch (Throwable t) {
            Logx.w("pullFromProvider failed", t);
        }
        try {
            ModuleBroadcasts.requestRulesFromApp(context);
        } catch (Throwable t) {
            Logx.w("requestRulesFromApp failed", t);
        }
        try {
            sendStatus(context);
        } catch (Throwable t) {
            Logx.w("sendStatus failed", t);
        }
        try {
            // 此时 context 是真实的 App Context（包名/uid 匹配），两个 key 都能写进去
            Diag.writeLoaded(context);
            Diag.write(context);
        } catch (Throwable t) {
            Logx.w("diag write failed", t);
        }
        try {
            ProviderPoller.start(context);
        } catch (Throwable t) {
            Logx.w("start poller failed", t);
        }
    }

    static void applyRules(String json, String source) {
        if (json == null) {
            return;
        }
        RuleSet set = RuleSet.fromJson(json);
        HookRuntime.rules = set;
        Xlog.step("rules applied from " + source + ": " + set.size() + " 条");
        Context context = HookRuntime.anyContext();
        if (context != null) {
            saveCache(context, set.toJson());
            sendStatus(context);
            Diag.write(Ctx.settingsContext());
        }
    }

    static void sendStatus(Context context) {
        try {
            if (context == null) {
                return;
            }
            ModuleBroadcasts.pushStatus(context, HookStatus.buildJson());
            Diag.write(Ctx.settingsContext());
        } catch (Throwable t) {
            Logx.w("sendStatus failed", t);
        }
    }

    // ------------------------------------------------------------------
    // 广播
    // ------------------------------------------------------------------

    private static final BroadcastReceiver RECEIVER = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                if (intent == null) {
                    return;
                }
                String action = intent.getAction();
                if (action == null) {
                    return;
                }
                if (!trustedSender(this, context)) {
                    Logx.w("drop broadcast from untrusted sender: " + action);
                    return;
                }
                lastBroadcastAt = android.os.SystemClock.elapsedRealtime();
                if (ModuleContract.ACTION_RULES_PUSH.equals(action)) {
                    applyRules(intent.getStringExtra(ModuleContract.EXTRA_RULES_JSON), "broadcast");
                } else if (ModuleContract.ACTION_FOREGROUND_PUSH.equals(action)) {
                    ForegroundTracker.onPushed(
                            intent.getStringExtra(ModuleContract.EXTRA_PACKAGE),
                            intent.getStringExtra(ModuleContract.EXTRA_ACTIVITY));
                } else if (ModuleContract.ACTION_STATUS_REQUEST.equals(action)) {
                    sendStatus(context);
                }
            } catch (Throwable t) {
                Logx.w("receiver failed", t);
            }
        }
    };

    private static void registerReceiver(Context context) {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ModuleContract.ACTION_RULES_PUSH);
        filter.addAction(ModuleContract.ACTION_FOREGROUND_PUSH);
        filter.addAction(ModuleContract.ACTION_STATUS_REQUEST);
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(RECEIVER, filter, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(RECEIVER, filter);
        }
        Logx.i("broadcast receiver registered (exported)");
    }

    /**
     * 只接受来自本模块 App（或 SystemUI 自己）的广播。
     * 判断不出来时放行：这条链路最多只会"重载规则表"，不执行任何外部指令。
     */
    private static boolean trustedSender(BroadcastReceiver receiver, Context context) {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                String pkg = receiver.getSentFromPackage();
                if (pkg != null) {
                    return ModuleContract.MODULE_PACKAGE.equals(pkg)
                            || ModuleContract.SYSTEMUI_PACKAGE.equals(pkg);
                }
                int uid = receiver.getSentFromUid();
                if (uid > 0 && context != null) {
                    String[] packages = context.getPackageManager().getPackagesForUid(uid);
                    if (packages != null) {
                        for (String p : packages) {
                            if (ModuleContract.MODULE_PACKAGE.equals(p)) {
                                return true;
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Provider 兜底
    // ------------------------------------------------------------------

    private static void pullFromProvider(Context context) {
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(ModuleContract.RULES_URI, null, null, null, null);
            if (cursor == null) {
                Logx.i("provider pull: 无结果（包可见性/SELinux 拦截属正常，主通道是广播）");
                return;
            }
            if (!cursor.moveToFirst()) {
                Logx.i("provider pull: 空结果");
                return;
            }
            applyRules(cursor.getString(0), "provider");
        } catch (Throwable t) {
            Logx.w("provider pull failed（可接受，主通道是广播）", t);
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 本地缓存（SystemUI 自己的私有目录）
    // ------------------------------------------------------------------

    private static File cacheFile(Context context) {
        return new File(new File(context.getFilesDir(), CACHE_DIR), CACHE_FILE);
    }

    private static void loadCache(Context context) {
        File file = cacheFile(context);
        if (!file.exists()) {
            Logx.i("no rules cache yet: " + file.getAbsolutePath());
            return;
        }
        FileInputStream in = null;
        try {
            in = new FileInputStream(file);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            applyRules(out.toString("UTF-8"), "cache");
        } catch (Throwable t) {
            Logx.w("loadCache read failed", t);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void saveCache(Context context, String json) {
        FileOutputStream out = null;
        try {
            File file = cacheFile(context);
            File dir = file.getParentFile();
            if (dir != null && !dir.exists() && !dir.mkdirs()) {
                Logx.w("cache dir create failed: " + dir.getAbsolutePath());
                return;
            }
            File tmp = new File(file.getAbsolutePath() + ".tmp");
            out = new FileOutputStream(tmp);
            out.write((json == null ? "" : json).getBytes("UTF-8"));
            out.flush();
            out.close();
            out = null;
            if (!tmp.renameTo(file)) {
                Logx.w("cache rename failed");
            }
        } catch (Throwable t) {
            Logx.w("saveCache failed", t);
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String safePackage(Context context) {
        try {
            return String.valueOf(context.getPackageName());
        } catch (Throwable t) {
            return "?";
        }
    }
}
