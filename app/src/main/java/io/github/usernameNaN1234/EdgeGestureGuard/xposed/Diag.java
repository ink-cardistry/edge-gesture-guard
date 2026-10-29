package io.github.usernameNaN1234.EdgeGestureGuard.xposed;

import android.content.Context;
import android.provider.Settings;

import io.github.usernameNaN1234.EdgeGestureGuard.common.Logx;
import io.github.usernameNaN1234.EdgeGestureGuard.common.ModuleContract;

/**
 * 把 Hook 进程的自检摘要写进 Settings.Global。
 *
 * SystemUI 是 platform 签名 priv-app，持有 WRITE_SECURE_SETTINGS；
 * 而任何 App 读 Settings.Global 都不需要权限，因此这条通道可以
 * 在"广播/Provider 全都不通"的情况下证明模块是否已被注入。
 *
 * 读不到不代表一定没注入（也可能 SELinux 拒绝写入），所以 UI 会同时参考广播结果。
 */
final class Diag {

    private Diag() {
    }

    /** 迟到补写"已加载"标记：早期（SystemContext）会被包名/uid 校验拒绝，拿到 App Context 后再写一次。 */
    static void writeLoaded(Context context) {
        if (context == null) {
            return;
        }
        try {
            String value = "v=" + HookRuntime.status.moduleVersion
                    + ";ts=" + System.currentTimeMillis() + ";ctx=late";
            Settings.Global.putString(context.getContentResolver(),
                    ModuleContract.LOADED_GLOBAL_KEY, value);
            Xlog.step("marker(late) ok: " + value);
        } catch (Throwable t) {
            Xlog.step("marker(late) failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    static void write(Context context) {
        if (context == null) {
            return;
        }
        try {
            String value = "v=1.8.1"
                    + ";ts=" + System.currentTimeMillis()
                    + ";edge=" + (HookRuntime.status.edgeHooked ? 1 : 0)
                    + ";legacy=" + HookRuntime.legacyHooks
                    + ";modern=" + HookRuntime.modernHooks
                    + ";bottom=" + HookRuntime.status.bottomState
                    + ";bottomSeen=" + HookRuntime.bottomZoneSeen
                    + ";bottomBlk=" + HookRuntime.bottomZoneBlocked
                    + ";shade=" + HookRuntime.status.shadeState
                    + ";shadeBlk=" + HookRuntime.shadeBlocked
                    + ";armed=" + (ArmController.isArmed() ? 1 : 0)
                    + ";topSeen=" + HookRuntime.topZoneSeen
                    + ";rules=" + (HookRuntime.rules == null ? 0 : HookRuntime.rules.size())
                    + ";fg=" + HookRuntime.status.foregroundStrategy;
            Settings.Global.putString(context.getContentResolver(), ModuleContract.DIAG_GLOBAL_KEY, value);
            Xlog.step("diag ok: " + value);
        } catch (Throwable t) {
            Xlog.step("diag failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }
}
