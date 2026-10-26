package com.edgeguard.gesture.app;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;

import com.edgeguard.gesture.common.Logx;
import com.edgeguard.gesture.common.ModuleContract;
import com.edgeguard.gesture.common.RuleSet;
import com.edgeguard.gesture.common.RuleStore;
import com.edgeguard.gesture.common.StatusReport;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;

/**
 * 诊断导出：把「SystemUI 里的 Hook 诊断日志」+「完整状态报告」写成文本文件。
 *
 * 为什么不用 logcat：用户要从 logcat 里捞行再复制太麻烦，而且手机上没有方便的管道。
 * 链路是：
 *   SystemUI(Hook) 把日志收进内存环形缓冲 -> 随 StatusReport.diag 广播给 App
 *   -> 本类在后台线程写文件
 *
 * 落点优先级：
 *   1) getExternalMediaDirs()  = /storage/emulated/0/Android/media/com.edgeguard.gesture/
 *      —— App 无需任何权限即可写；其它有存储权限的工具（含 DSH）可以直接读，
 *         而 /Android/data/<pkg> 对其他 App 是禁止访问的，所以不用它当主路径。
 *   2) getExternalFilesDir()   —— 兜底（别人读不到，但至少文件存在）
 */
public final class DiagExporter {

    private static final String LATEST = "edgeguard-diag-latest.txt";
    private static final String PREFIX = "edgeguard-diag-";
    private static final int KEEP = 5;

    private static volatile Handler worker;
    private static volatile String lastPath = "";
    private static volatile long lastAt;

    private DiagExporter() {
    }

    public static String lastPath() {
        return lastPath;
    }

    /** 目标目录（Toast 里显示给用户/给 AI 读的路径）。 */
    public static String dirHint(Context context) {
        File dir = targetDir(context);
        return dir == null ? "?" : dir.getAbsolutePath();
    }

    public static String latestName() {
        return LATEST;
    }

    public static long lastAt() {
        return lastAt;
    }

    /** 后台导出（状态回推、用户点按钮都走这里）。 */
    public static void exportAsync(final Context context, final StatusReport report,
                                   final String reason) {
        if (context == null) {
            return;
        }
        try {
            Handler h = worker();
            if (h == null) {
                return;
            }
            final Context app = context.getApplicationContext();
            h.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        exportNow(app == null ? context : app, report, reason);
                    } catch (Throwable t) {
                        Logx.w("exportNow failed", t);
                    }
                }
            });
        } catch (Throwable t) {
            Logx.w("exportAsync failed", t);
        }
    }

    private static Handler worker() {
        Handler h = worker;
        if (h != null) {
            return h;
        }
        synchronized (DiagExporter.class) {
            if (worker == null) {
                HandlerThread thread = new HandlerThread("edgeguard-diag");
                thread.start();
                worker = new Handler(thread.getLooper());
            }
            return worker;
        }
    }

    /** 真正写文件；返回写入路径（失败返回 null）。 */
    public static String exportNow(Context context, StatusReport report, String reason) {
        File dir = targetDir(context);
        if (dir == null) {
            return null;
        }
        if (!dir.exists() && !dir.mkdirs()) {
            Logx.w("diag dir create failed: " + dir.getAbsolutePath());
            return null;
        }
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        File latest = new File(dir, LATEST);
        File copy = new File(dir, PREFIX + stamp + ".txt");
        String text = render(context, report, reason);
        boolean ok = write(latest, text);
        write(copy, text);
        prune(dir);
        if (ok) {
            lastPath = latest.getAbsolutePath();
            lastAt = System.currentTimeMillis();
        }
        Logx.i("diag exported: " + latest.getAbsolutePath() + " (" + text.length() + " chars)");
        return latest.getAbsolutePath();
    }

    private static File targetDir(Context context) {
        try {
            File[] media = context.getExternalMediaDirs();
            if (media != null) {
                for (File f : media) {
                    if (f != null) {
                        return new File(f, "");
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            return context.getExternalFilesDir(null);
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean write(File file, String text) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(file);
            out.write(text.getBytes("UTF-8"));
            out.flush();
            return true;
        } catch (Throwable t) {
            Logx.w("diag write failed: " + file.getAbsolutePath(), t);
            return false;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void prune(File dir) {
        try {
            File[] files = dir.listFiles();
            if (files == null) {
                return;
            }
            ArrayList<File> olds = new ArrayList<>();
            for (File f : files) {
                String name = f.getName();
                if (name.startsWith(PREFIX) && name.endsWith(".txt")) {
                    olds.add(f);
                }
            }
            if (olds.size() <= KEEP) {
                return;
            }
            olds.sort(new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return Long.compare(b.lastModified(), a.lastModified());
                }
            });
            for (int i = KEEP; i < olds.size(); i++) {
                //noinspection ResultOfMethodCallIgnored
                olds.get(i).delete();
            }
        } catch (Throwable ignored) {
        }
    }

    private static String render(Context context, StatusReport r, String reason) {
        StringBuilder sb = new StringBuilder(8192);
        sb.append("# EdgeGestureGuard 诊断导出\n");
        sb.append("时间: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date())).append('\n');
        sb.append("原因: ").append(reason == null ? "" : reason).append('\n');
        sb.append("App 版本: ").append(versionName(context)).append('\n');
        sb.append("导出路径: ").append(lastPath).append('\n');
        if (r == null) {
            sb.append("\n(没有收到状态报告：模块可能没注入 SystemUI，或广播通道不通)\n");
            return sb.toString();
        }
        sb.append("\n## 状态\n");
        sb.append("moduleVersion : ").append(r.moduleVersion).append('\n');
        sb.append("edgeHooked    : ").append(r.edgeHooked).append('\n');
        sb.append("edgeTarget    : ").append(nz(r.edgeTarget)).append('\n');
        sb.append("shadeState    : ").append(r.shadeState).append('\n');
        sb.append("shadeTarget   : ").append(nz(r.shadeTarget)).append('\n');
        sb.append("bottomState   : ").append(r.bottomState).append('\n');
        sb.append("bottomMeasure : ").append(nz(r.bottomMeasure)).append('\n');
        sb.append("bottomTarget  : ").append(nz(r.bottomTarget)).append('\n');
        sb.append("zoneConfig    : ").append(nz(r.zoneConfig)).append('\n');
        sb.append("verifyOnly    : ").append(r.verifyOnly).append('\n');
        sb.append("armed         : ").append(r.armed)
                .append(" (source=").append(nz(r.armedSource)).append(")\n");
        sb.append("foreground    : ").append(nz(r.foregroundPackage)).append('/')
                .append(nz(r.foregroundActivity))
                .append(" [").append(nz(r.foregroundStrategy)).append("]\n");
        sb.append("foregroundErr : ").append(nz(r.foregroundError)).append('\n');
        sb.append("ruleCount     : ").append(r.ruleCount).append('\n');
        sb.append("launcherState : ").append(nz(r.launcherState)).append('\n');
        sb.append("edgeMetrics   : ").append(nz(r.edgeMetrics)).append('\n');
        sb.append("timestamp     : ").append(r.timestamp).append('\n');

        sb.append("\n## 策略 B（Launcher 进程回报）\n");
        android.content.SharedPreferences sp = context.getSharedPreferences(
                ModuleContract.PREFS_RULES, Context.MODE_PRIVATE);
        String launcherStatus = sp.getString(ModuleContract.PREF_LAUNCHER_STATUS, null);
        String launcherDiag = sp.getString(ModuleContract.PREF_LAUNCHER_DIAG, null);
        sb.append("status    : ").append(launcherStatus == null ? "(未注入：作用域没勾或没重启)" : launcherStatus).append('\n');
        if (launcherDiag != null && launcherDiag.length() > 0) {
            sb.append("--- 桌面进程日志 ---\n").append(launcherDiag).append('\n');
        }

        sb.append("\n## 规则\n");
        try {
            RuleSet set = new RuleStore(context).load();
            sb.append(set.toJson()).append('\n');
        } catch (Throwable t) {
            sb.append("(读取规则失败: ").append(t).append(")\n");
        }

        sb.append("\n## Hook 进程日志（尾 ").append(r.diag.size()).append(" 行）\n");
        for (String line : r.diag) {
            sb.append(line).append('\n');
        }
        sb.append("\n## 原始状态 JSON\n").append(r.toJson()).append('\n');
        return sb.toString();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String versionName(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }
}
