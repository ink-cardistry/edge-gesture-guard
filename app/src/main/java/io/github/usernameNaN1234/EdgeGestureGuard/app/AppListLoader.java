package io.github.usernameNaN1234.EdgeGestureGuard.app;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;

import io.github.usernameNaN1234.EdgeGestureGuard.common.Logx;
import io.github.usernameNaN1234.EdgeGestureGuard.common.ModuleContract;
import io.github.usernameNaN1234.EdgeGestureGuard.common.RuleStore;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 后台线程加载应用列表：
 *  - 默认只显示"有启动图标的应用"；
 *  - 勾选"包含系统应用"后再把系统组件加进来；
 *  - 已配置过规则的应用永远显示（避免用户找不到自己配过的包）。
 */
public final class AppListLoader {

    public interface Callback {
        void onLoaded(List<AppEntry> apps);
    }

    private final Context context;

    public AppListLoader(Context context) {
        Context app = context.getApplicationContext();
        this.context = app == null ? context : app;
    }

    public void loadAsync(final boolean includeSystem, final Callback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<AppEntry> apps = load(includeSystem);
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        if (callback != null) {
                            callback.onLoaded(apps);
                        }
                    }
                });
            }
        }, "edgeguard-applist").start();
    }

    private List<AppEntry> load(boolean includeSystem) {
        List<AppEntry> result = new ArrayList<>();
        try {
            PackageManager pm = context.getPackageManager();
            Set<String> launcherPackages = new HashSet<>();
            Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> resolved = pm.queryIntentActivities(launcher, 0);
            if (resolved != null) {
                for (ResolveInfo info : resolved) {
                    if (info != null && info.activityInfo != null && info.activityInfo.packageName != null) {
                        launcherPackages.add(info.activityInfo.packageName);
                    }
                }
            }
            Set<String> configured = new HashSet<>(new RuleStore(context).configuredPackages());

            List<ApplicationInfo> installed = pm.getInstalledApplications(0);
            if (installed != null) {
                for (ApplicationInfo info : installed) {
                    if (info == null) {
                        continue;
                    }
                    String pkg = info.packageName;
                    if (pkg == null || pkg.length() == 0 || ModuleContract.MODULE_PACKAGE.equals(pkg)) {
                        continue;
                    }
                    boolean system = (info.flags
                            & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
                    boolean hasLauncher = launcherPackages.contains(pkg);
                    if (!hasLauncher && !(includeSystem && system) && !configured.contains(pkg)) {
                        continue;
                    }
                    String label = pkg;
                    try {
                        CharSequence cs = info.loadLabel(pm);
                        if (cs != null && cs.length() > 0) {
                            label = cs.toString();
                        }
                    } catch (Throwable ignored) {
                    }
                    Drawable icon = null;
                    try {
                        icon = info.loadIcon(pm);
                    } catch (Throwable ignored) {
                    }
                    result.add(new AppEntry(pkg, label, system, icon, info));
                }
            }
            final Collator collator = Collator.getInstance(Locale.getDefault());
            Collections.sort(result, new Comparator<AppEntry>() {
                @Override
                public int compare(AppEntry a, AppEntry b) {
                    String la = a.label == null ? "" : a.label;
                    String lb = b.label == null ? "" : b.label;
                    int byLabel = collator.compare(la, lb);
                    if (byLabel != 0) {
                        return byLabel;
                    }
                    return a.packageName.compareTo(b.packageName);
                }
            });
            Logx.i("app list loaded: " + result.size() + " (includeSystem=" + includeSystem + ")");
        } catch (Throwable t) {
            Logx.w("load app list failed", t);
        }
        return result;
    }
}
