package com.edgeguard.gesture.xposed;

import android.app.Application;
import android.content.Context;

import com.edgeguard.gesture.common.Logx;
import com.edgeguard.gesture.common.ModuleContract;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed 模块入口（assets/xposed_init 指向本类）。
 *
 * 只对 com.android.systemui 生效：其它进程（含目标游戏/应用）直接 return，
 * 因此目标应用里不会加载任何 Hook 代码。
 *
 * 日志：logcat -s EdgeGestureGuard
 */
public final class XposedEntry implements IXposedHookLoadPackage {

    private static volatile boolean pipeInited;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            if (lpparam == null || lpparam.packageName == null) {
                return;
            }
            if (!ModuleContract.SYSTEMUI_PACKAGE.equals(lpparam.packageName)) {
                return;   // 作用域外，什么都不做
            }
            // 先无条件打日志：这是"LSPosed 到底有没有把模块派发进来"的唯一证据，
            // 任何提前 return 都必须在它之后，否则排查时会完全看不到痕迹。
            Xlog.step("dispatched, pkg=" + lpparam.packageName + ", process=" + lpparam.processName
                    + ", v=" + HookRuntime.status.moduleVersion);

            // 主进程才真正干活：:screenshot / :tuner 这类子进程没有导航栏实例。
            String processName = lpparam.processName;
            if (processName != null && !processName.equals(lpparam.packageName)) {
                Logx.i("subprocess " + processName + " -> skip hooks");
                return;
            }

            // 0) 最早期的注入标记：即使后面全部初始化失败，App 侧也能看到"模块确实进过 SystemUI"
            writeLoadedMarker();

            // 1) 先装手势 Hook（不依赖 Context，越早越好）
            try {
                GestureHooks.install(lpparam.classLoader);
            } catch (Throwable t) {
                Logx.e("GestureHooks.install failed", t);
                Xlog.step("GestureHooks.install FAILED: " + t);
            }

            // 2) 再初始化 IPC。
            //    实测：handleLoadPackage 阶段 appContext 恒为 null，而 SystemUI 的 Application
            //    也不一定回调 super.onCreate，所以光靠 hook onCreate 不可靠。
            //    这里做三件事：a) 先拿到兜底 Context；b) 立刻启动 provider 轮询（不依赖广播）；
            //    c) 主线程定时重试拿真实 App Context，拿到后注册广播接收器。
            Context systemCtx = Ctx.systemContext();
            HookRuntime.pipeContext = systemCtx;
            Xlog.step("pipeContext=" + (systemCtx == null ? "null" : systemCtx.getPackageName()));
            if (systemCtx != null) {
                try {
                    ProviderPoller.start(systemCtx);
                    Xlog.step("provider poller armed");
                } catch (Throwable t) {
                    Xlog.step("poller start failed: " + t);
                }
            }

            Context context = currentContext();
            Xlog.step("appContext=" + (context == null ? "null" : context.getPackageName()));
            if (context != null) {
                initPipe(context);
                return;
            }
            scheduleContextRetry(0);
            try {
                XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (param.thisObject instanceof Context) {
                                initPipe((Context) param.thisObject);
                            }
                        } catch (Throwable t) {
                            Logx.e("init pipe after Application.onCreate failed", t);
                        }
                    }
                });
                Logx.i("context not ready yet, waiting for Application.onCreate");
            } catch (Throwable t) {
                Logx.e("hook Application.onCreate failed", t);
            }
        } catch (Throwable t) {
            // 入口层再兜一层：绝不让异常冒泡影响 SystemUI 启动
            Logx.e("handleLoadPackage failed", t);
        }
    }

    /** 写 Settings.Global 的"已加载"标记（SystemUI 有 WRITE_SECURE_SETTINGS，App 读不需要权限）。 */
    private static void writeLoadedMarker() {
        try {
            Context context = Ctx.settingsContext();
            String source = "settings";
            if (context == null) {
                Xlog.step("marker skipped: no context");
                return;
            }
            String value = "v=" + HookRuntime.status.moduleVersion
                    + ";ts=" + System.currentTimeMillis() + ";ctx=" + source;
            android.provider.Settings.Global.putString(
                    context.getContentResolver(), ModuleContract.LOADED_GLOBAL_KEY, value);
            Xlog.step("marker ok: " + value);
        } catch (Throwable t) {
            Xlog.step("marker failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /**
     * 定时重试拿真实的 App Context（Application 创建后才有）。
     * 比只 hook Application.onCreate 可靠：SystemUI 的 Application 可能不回调 super.onCreate。
     */
    private static void scheduleContextRetry(final int attempt) {
        if (pipeInited || attempt > 8) {
            return;
        }
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        Context context = Ctx.appContext();
                        if (context != null) {
                            Xlog.step("appContext ready after " + (attempt * 300) + "ms");
                            initPipe(context);
                        } else {
                            scheduleContextRetry(attempt + 1);
                        }
                    } catch (Throwable t) {
                        Xlog.step("context retry failed: " + t);
                    }
                }
            }, 300L);
        } catch (Throwable t) {
            Xlog.step("scheduleContextRetry failed: " + t);
        }
    }

    private static void initPipe(Context context) {
        if (context == null || pipeInited) {
            return;
        }
        pipeInited = true;
        try {
            Xlog.step("pipe init, ctx=" + context.getPackageName());
            RulesPipe.init(context);
            Xlog.step("pipe init done");
        } catch (Throwable t) {
            Logx.e("RulesPipe.init failed", t);
            Xlog.step("pipe init FAILED: " + t);
        }
        // 推送式前台跟踪：装好后触摸回调不再做 Binder 查询。
        // 注册失败时自动退回"按需查询"（行为与旧版一致），不影响功能。
        try {
            if (!stackWatchStarted) {
                stackWatchStarted = true;
                if (TaskStackWatch.start()) {
                    ForegroundTracker.enableTracking();
                    ForegroundTracker.refreshAsync();
                    Xlog.step("foreground tracking: push mode (触摸路径零 Binder)");
                } else {
                    Xlog.step("foreground tracking: fallback (按需查询)");
                }
            }
        } catch (Throwable t) {
            Xlog.step("task-stack setup failed: " + t);
        }
    }

    private static volatile boolean stackWatchStarted;

    private static Context currentContext() {
        // 纯反射获取，绝不 import LSPosed 专有类（见 Ctx 的注释）
        return Ctx.appContext();
    }
}
