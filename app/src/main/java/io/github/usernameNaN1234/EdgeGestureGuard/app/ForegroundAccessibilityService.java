package io.github.usernameNaN1234.EdgeGestureGuard.app;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;

import io.github.usernameNaN1234.EdgeGestureGuard.common.Logx;
import io.github.usernameNaN1234.EdgeGestureGuard.common.ModuleBroadcasts;
import io.github.usernameNaN1234.EdgeGestureGuard.common.ModuleContract;
import io.github.usernameNaN1234.EdgeGestureGuard.common.RuleStore;

/**
 * 可选前台检测助手（默认关闭，由用户在系统设置里手动启用）。
 *
 * 为什么需要它：SystemUI 进程不一定持有 REAL_GET_TASKS / PACKAGE_USAGE_STATS，
 * 一旦两档都失败，Hook 就拿不到前台包名。这个无障碍服务只监听
 * TYPE_WINDOW_STATE_CHANGED（窗口切换）并把包名/类名广播给 SystemUI，
 * 不读取窗口内容（canRetrieveWindowContent=false）、不联网、不存储。
 */
public final class ForegroundAccessibilityService extends AccessibilityService {

    private String lastPackage = "";
    private String lastActivity = "";
    private long lastPushAt;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        try {
            if (event == null) {
                return;
            }
            if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                return;
            }
            CharSequence pkgCs = event.getPackageName();
            if (pkgCs == null) {
                return;
            }
            String pkg = pkgCs.toString();
            if (!isInteresting(pkg)) {
                return;
            }
            CharSequence clsCs = event.getClassName();
            String activity = clsCs == null ? "" : clsCs.toString();
            long now = SystemClock.elapsedRealtime();
            if (pkg.equals(lastPackage) && activity.equals(lastActivity) && now - lastPushAt < 500L) {
                return;
            }
            lastPackage = pkg;
            lastActivity = activity;
            lastPushAt = now;
            ModuleBroadcasts.pushForeground(this, pkg, activity);
        } catch (Throwable t) {
            Logx.w("onAccessibilityEvent failed", t);
        }
    }

    @Override
    public void onInterrupt() {
        // 不需要中断处理
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Logx.i("foreground helper (accessibility) connected");
        try {
            // 顺便做一次规则同步，保证 SystemUI 一定拿到最新规则
            ModuleBroadcasts.pushRules(this, new RuleStore(this).load());
        } catch (Throwable t) {
            Logx.w("push rules on service connect failed", t);
        }
    }

    private static boolean isInteresting(String pkg) {
        if (pkg == null || pkg.length() == 0) {
            return false;
        }
        if (ModuleContract.MODULE_PACKAGE.equals(pkg)) {
            return false;
        }
        if (ModuleContract.SYSTEMUI_PACKAGE.equals(pkg)) {
            return false;
        }
        return !"android".equals(pkg);
    }
}
