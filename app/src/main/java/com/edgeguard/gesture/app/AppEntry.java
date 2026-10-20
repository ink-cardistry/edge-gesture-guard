package com.edgeguard.gesture.app;

import android.content.pm.ApplicationInfo;
import android.graphics.drawable.Drawable;

import java.util.Locale;

/** 列表项数据（图标在后台线程一次性 load，避免滚动时卡顿）。 */
public final class AppEntry {

    public final String packageName;
    public final String label;
    public final boolean systemApp;
    public final Drawable icon;
    public final ApplicationInfo info;

    public AppEntry(String packageName, String label, boolean systemApp, Drawable icon, ApplicationInfo info) {
        this.packageName = packageName;
        this.label = label;
        this.systemApp = systemApp;
        this.icon = icon;
        this.info = info;
    }

    public String searchKey() {
        String l = label == null ? "" : label;
        return (l + " " + packageName).toLowerCase(Locale.ROOT);
    }
}
