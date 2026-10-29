package io.github.usernameNaN1234.EdgeGestureGuard.xposed;

import android.view.InputEvent;

import io.github.usernameNaN1234.EdgeGestureGuard.common.Logx;

import java.lang.reflect.Method;

/**
 * 输入事件收尾（A16/17 新架构的关键点）。
 *
 * gesture monitor 通道拿到的事件**必须**由处理方调用 finishInputEvent(event, handled)，
 * 否则 InputDispatcher 永远等不到"已处理"，表现就是手势后触摸卡死 / Input dispatching timed out。
 *
 * 原则：宁可放弃屏蔽，绝不阻断输入 —— 任何一步失败都返回 false，调用方据此放行原逻辑。
 * 这个类被 SystemUI 的返回手势通道和（可选的）Launcher 底部手势通道共用。
 */
final class InputSink {

    private InputSink() {
    }

    /**
     * 反射调用 android.view.InputEventReceiver#finishInputEvent(InputEvent, boolean)（@hide）。
     *
     * @return true = 已经收尾，可以安全阻止原逻辑；false = 没成功，必须放行
     */
    static boolean finish(Object receiver, InputEvent event, boolean handled) {
        if (receiver == null || event == null) {
            return false;
        }
        Method method = null;
        Class<?> cls = receiver.getClass();
        while (cls != null && cls != Object.class && method == null) {
            try {
                method = cls.getDeclaredMethod("finishInputEvent", InputEvent.class, boolean.class);
            } catch (Throwable ignored) {
                cls = cls.getSuperclass();
            }
        }
        if (method == null) {
            return false;
        }
        try {
            method.setAccessible(true);
            method.invoke(receiver, event, handled);
            return true;
        } catch (Throwable t) {
            Logx.w("finishInputEvent invoke failed", t);
            return false;
        }
    }

    /**
     * 让被 Hook 的方法"看起来已经处理完了"。
     *
     * 语义差异很重要：
     *  - onTouchEvent / onInterceptTouchEvent / dispatchTouchEvent 返回 boolean，
     *    这里返回 true 表示"这条事件由我们消费掉了"，下游不会再处理；
     *  - void 方法返回 null。
     * 返回值类型判断失败时按 void 处理（最坏情况是原方法被跳过，仍不影响输入通道）。
     */
    static void consumeReturn(de.robv.android.xposed.XC_MethodHook.MethodHookParam param) {
        if (param == null) {
            return;
        }
        try {
            Object method = param.method;
            Class<?> returnType = method instanceof Method
                    ? ((Method) method).getReturnType() : void.class;
            if (returnType == void.class || returnType == Void.class) {
                param.setResult(null);
            } else if (returnType == boolean.class || returnType == Boolean.class) {
                param.setResult(Boolean.TRUE);
            } else if (returnType == int.class || returnType == Integer.class) {
                param.setResult(1);
            } else if (returnType == long.class || returnType == Long.class) {
                param.setResult(1L);
            } else if (returnType == float.class || returnType == Float.class) {
                param.setResult(1f);
            } else if (returnType == double.class || returnType == Double.class) {
                param.setResult(1d);
            } else {
                param.setResult(null);
            }
        } catch (Throwable t) {
            Logx.w("consumeReturn failed", t);
        }
    }
}
