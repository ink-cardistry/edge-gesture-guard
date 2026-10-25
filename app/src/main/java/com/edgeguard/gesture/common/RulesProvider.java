package com.edgeguard.gesture.common;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Process;

/**
 * 兜底拉取通道：SystemUI 进程内的 Hook 可以直接 query 最新规则。
 *
 * 说明：Android 11+ 的包可见性 / SELinux 可能让这条通道失败，
 * 因此它只是"尽力而为"的兜底，主通道始终是广播推送 + SystemUI 私有目录缓存。
 */
public final class RulesProvider extends ContentProvider {

    public static final String COLUMN_JSON = "json";

    private static final int SHELL_UID = 2000;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        try {
            Context ctx = getContext();
            if (ctx == null) {
                return null;
            }
            int uid = Binder.getCallingUid();
            if (!isTrustedCaller(ctx, uid)) {
                Logx.w("provider: rejected caller uid=" + uid);
                return null;
            }
            String json = new RuleStore(ctx).load().toJson();
            MatrixCursor cursor = new MatrixCursor(new String[]{COLUMN_JSON}, 1);
            cursor.addRow(new Object[]{json});
            return cursor;
        } catch (Throwable t) {
            Logx.w("provider query failed", t);
            return null;
        }
    }

    private static boolean isTrustedCaller(Context ctx, int uid) {
        try {
            if (uid == Process.myUid() || uid == Process.SYSTEM_UID || uid == SHELL_UID) {
                return true;
            }
            String[] packages = ctx.getPackageManager().getPackagesForUid(uid);
            if (packages != null) {
                for (String p : packages) {
                    if (ModuleContract.MODULE_PACKAGE.equals(p)
                            || ModuleContract.SYSTEMUI_PACKAGE.equals(p)
                            || ModuleContract.isLauncherPackage(p)) {
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
            Logx.w("provider: caller check failed", t);
        }
        return false;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.item/vnd." + ModuleContract.MODULE_PACKAGE + ".rules";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
