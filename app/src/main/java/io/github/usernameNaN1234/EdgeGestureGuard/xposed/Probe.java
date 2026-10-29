package io.github.usernameNaN1234.EdgeGestureGuard.xposed;

import io.github.usernameNaN1234.EdgeGestureGuard.common.Logx;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Hook 目标探测。
 *
 * 探测顺序（越往后越"抗版本变化"）：
 *  1) 直接按历史类名加载（AOSP 12-17 仍是 com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler）；
 *  2) 扫描宿主类（NavigationBar / NavigationBarView / ...）的字段类型，
 *     字段类型简单名里含 "EdgeBack" 的即目标类 —— 这样即使类被重命名/移包也能找到；
 *  3) 找不到 -> 返回 null，上层记录日志并降级（不屏蔽任何手势，保持系统默认行为）。
 *
 * LineageOS 24 / Android 17 可能合入额外手势补丁，所以第 2 步是主力，
 * 第 1 步只是加速；每次探测结果都会打到 logcat: 先看 "probe:" 前缀。
 */
final class Probe {

    private Probe() {
    }

    /** 历史/当前已知的类名（仅提示，不是硬依赖）。 */
    private static final String[] EDGE_CLASS_HINTS = {
            "com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler",
            "com.android.systemui.navigationbar.gestural.EdgeBackGestureHandlerImpl",
            "com.android.systemui.navigationbar.EdgeBackGestureHandler",
            "com.android.systemui.statusbar.phone.EdgeBackGestureHandler",
            "com.android.systemui.navigationbar.gestural.BackGestureHandler",
    };

    /** 可能持有手势处理器的宿主类（A12 ~ A17 形态）。 */
    private static final String[] OWNER_CLASS_HINTS = {
            "com.android.systemui.navigationbar.NavigationBar",
            "com.android.systemui.navigationbar.NavigationBarView",
            "com.android.systemui.navigationbar.views.NavigationBar",
            "com.android.systemui.navigationbar.views.NavigationBarView",
            "com.android.systemui.navigationbar.gestural.NavigationBarGestureHelper",
            "com.android.systemui.navigationbar.gestural.GestureHelper",
            "com.android.systemui.navigationbar.NavigationBarComponent",
    };

    /** 可能负责底部 handle/手势的类（不保证存在）。 */
    private static final String[] HOME_HANDLE_HINTS = {
            "com.android.systemui.navigationbar.gestural.HomeHandle",
            "com.android.systemui.navigationbar.views.HomeHandle",
            "com.android.systemui.navigationbar.HomeHandle",
            "com.android.systemui.statusbar.phone.HomeHandle",
    };

    static Class<?> findEdgeHandler(ClassLoader cl) {
        for (String name : EDGE_CLASS_HINTS) {
            Class<?> c = Reflect.load(cl, name);
            if (c != null) {
                Logx.i("probe: edge handler resolved by hint name");
                return c;
            }
        }
        Class<?> byField = findByFieldType(cl, OWNER_CLASS_HINTS, "EdgeBack");
        if (byField != null) {
            Logx.i("probe: edge handler resolved by field-type scan -> " + byField.getName());
            return byField;
        }
        Logx.e("probe: edge handler NOT found (类名提示与字段扫描都失败)");
        return null;
    }

    /**
     * A16/A17 新架构：返回手势改由 DisplayBackGestureHandlerImpl 通过 InputMonitorCompat
     * 的 gesture monitor 接收事件（不再走 EdgeBackGestureHandler.onMotionEvent）。
     *
     * 探测顺序：
     *  1) 类名提示；
     *  2) EdgeBackGestureHandler 里返回类型名含 "BackGestureHandlerImpl" 的方法
     *     （本机实测 createDisplayBackGestureHandler(Display) 就是）；
     *  3) EdgeBackGestureHandler 的字段类型名含 "BackGestureHandlerImpl"。
     */
    static Class<?> findBackGestureImpl(ClassLoader cl, Class<?> edgeHandlerClass) {
        String[] hints = {
                "com.android.systemui.navigationbar.gestural.DisplayBackGestureHandlerImpl",
                "com.android.systemui.navigationbar.gestural.BackGestureHandlerImpl",
                "com.android.systemui.navigationbar.gestural.DisplayBackGestureHandler",
        };
        for (String name : hints) {
            Class<?> c = Reflect.load(cl, name);
            if (c != null) {
                Logx.i("probe: back-gesture impl by hint name");
                return c;
            }
        }
        if (edgeHandlerClass == null) {
            return null;
        }
        try {
            for (Method m : edgeHandlerClass.getDeclaredMethods()) {
                Class<?> returnType = m.getReturnType();
                if (returnType != null && returnType.getName().contains("BackGestureHandlerImpl")) {
                    Logx.i("probe: back-gesture impl by return type of " + m.getName()
                            + " -> " + returnType.getName());
                    return returnType;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> c = edgeHandlerClass;
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    Class<?> type = f.getType();
                    if (type != null && type.getName().contains("BackGestureHandlerImpl")) {
                        Logx.i("probe: back-gesture impl by field " + f.getName()
                                + " -> " + type.getName());
                        return type;
                    }
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    static Class<?> findHomeHandle(ClassLoader cl) {
        for (String name : HOME_HANDLE_HINTS) {
            Class<?> c = Reflect.load(cl, name);
            if (c != null) {
                Logx.i("probe: home handle resolved by hint name -> " + name);
                return c;
            }
        }
        Class<?> byField = findByFieldType(cl, OWNER_CLASS_HINTS, "HomeHandle");
        if (byField != null) {
            Logx.i("probe: home handle resolved by field-type scan -> " + byField.getName());
            return byField;
        }
        return null;
    }

    /**
     * 扫描候选宿主类的字段类型，找出简单名包含 token 的类型。
     * 例：NavigationBar 里 private final EdgeBackGestureHandler mEdgeBackGestureHandler;
     */
    private static Class<?> findByFieldType(ClassLoader cl, String[] owners, String token) {
        for (String ownerName : owners) {
            Class<?> owner = Reflect.load(cl, ownerName);
            if (owner == null) {
                continue;
            }
            Class<?> c = owner;
            int guard = 0;
            while (c != null && c != Object.class && guard++ < 6) {
                Field[] fields;
                try {
                    fields = c.getDeclaredFields();
                } catch (Throwable t) {
                    break;
                }
                for (Field f : fields) {
                    Class<?> type = f.getType();
                    if (type == null) {
                        continue;
                    }
                    String simple = type.getSimpleName();
                    if (simple != null && simple.contains(token)) {
                        Logx.i("probe: " + ownerName + "." + f.getName() + " : " + type.getName());
                        return type;
                    }
                }
                c = c.getSuperclass();
            }
        }
        return null;
    }
}
