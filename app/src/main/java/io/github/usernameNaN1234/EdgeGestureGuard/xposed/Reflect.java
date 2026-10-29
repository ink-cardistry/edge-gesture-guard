package io.github.usernameNaN1234.EdgeGestureGuard.xposed;

import android.view.InputEvent;
import android.view.MotionEvent;

import io.github.usernameNaN1234.EdgeGestureGuard.common.Logx;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 反射工具箱。
 *
 * 设计原则（对应需求里的"禁止硬编码方法名/字段名"）：
 *  - 一切探测都在这里完成，方法名/字段名只是"候选提示"；
 *  - 找不到就返回 null，由上层记录日志 + 降级，绝不抛异常；
 *  - 探测结果（类名、方法签名、字段名、边宽像素）都会打到 logcat。
 */
final class Reflect {

    private Reflect() {
    }

    static Class<?> load(ClassLoader cl, String name) {
        try {
            Class<?> c = Class.forName(name, false, cl);
            Logx.i("probe: class found " + name);
            return c;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 按候选名找字段（含父类）。找不到返回 null。 */
    static Field field(Class<?> cls, String... names) {
        if (cls == null || names == null) {
            return null;
        }
        for (String name : names) {
            Class<?> c = cls;
            while (c != null && c != Object.class) {
                try {
                    Field f = c.getDeclaredField(name);
                    try {
                        f.setAccessible(true);
                    } catch (Throwable ignored) {
                        // 隐藏 API 限制下可能失败，后面 get() 还会再失败一次，交给调用方兜底
                    }
                    return f;
                } catch (Throwable ignored) {
                    // 继续往父类找
                }
                c = c.getSuperclass();
            }
        }
        return null;
    }

    static int intField(Object target, Field f, int def) {
        if (target == null || f == null) {
            return def;
        }
        try {
            Object v = f.get(target);
            if (v instanceof Number) {
                return ((Number) v).intValue();
            }
        } catch (Throwable ignored) {
            // 失败即用默认值
        }
        return def;
    }

    static boolean booleanField(Object target, Field f, boolean def) {
        if (target == null || f == null) {
            return def;
        }
        try {
            Object v = f.get(target);
            if (v instanceof Boolean) {
                return (Boolean) v;
            }
        } catch (Throwable ignored) {
            // 失败即用默认值
        }
        return def;
    }

    static Object fieldValue(Object target, String... names) {
        if (target == null) {
            return null;
        }
        try {
            Field f = field(target.getClass(), names);
            if (f == null) {
                return null;
            }
            return f.get(target);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 类里所有字段（含父类）的 name:SimpleType，用于 logcat 里人工确认版本差异。 */
    static String describe(Class<?> cls) {
        StringBuilder sb = new StringBuilder();
        try {
            Class<?> c = cls;
            int guard = 0;
            while (c != null && c != Object.class && guard++ < 6) {
                Field[] fields;
                try {
                    fields = c.getDeclaredFields();
                } catch (Throwable t) {
                    break;
                }
                for (Field f : fields) {
                    if (sb.length() > 0) {
                        sb.append(", ");
                    }
                    sb.append(f.getName()).append(':').append(f.getType().getSimpleName());
                    if (sb.length() > 1200) {
                        sb.append(" ...");
                        return sb.toString();
                    }
                }
                c = c.getSuperclass();
            }
        } catch (Throwable t) {
            // ignore
        }
        return sb.toString();
    }

    static String signature(Method m) {
        if (m == null) {
            return "(null)";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(m.getDeclaringClass().getSimpleName()).append('#').append(m.getName()).append('(');
        Class<?>[] ps = m.getParameterTypes();
        for (int i = 0; i < ps.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(ps[i].getSimpleName());
        }
        return sb.append(')').toString();
    }

    /**
     * 收集触摸入口候选方法：非静态、单参数、参数类型可接收 MotionEvent 或 InputEvent。
     * 同时打印该类里所有"看起来像触摸入口"的方法名，方便确认版本差异。
     */
    static List<Method> touchEntryMethods(Class<?> cls) {
        Set<Method> out = new LinkedHashSet<>();
        if (cls == null) {
            return new ArrayList<>();
        }
        Class<?> c = cls;
        int guard = 0;
        while (c != null && c != Object.class && guard++ < 8) {
            Method[] methods;
            try {
                methods = c.getDeclaredMethods();
            } catch (Throwable t) {
                break;
            }
            for (Method m : methods) {
                int mod = m.getModifiers();
                if (Modifier.isAbstract(mod) || Modifier.isStatic(mod)) {
                    continue;
                }
                // 关键：绝不能 Hook 框架基类的实现（View / ViewGroup 的
                // onTouchEvent / dispatchTouchEvent / onInterceptTouchEvent 是全体 View 共享的），
                // 否则会影响到 SystemUI 里所有控件。只挂"ROM 自己在 SystemUI 包里声明"的方法。
                if (!isRomDeclared(m.getDeclaringClass())) {
                    continue;
                }
                // A15 及以前：onMotionEvent(MotionEvent)
                // A16/A17：onInputEvent(InputEvent) 或 onInputEvent(InputEvent, int)
                // 所以不能限定"单参数"，只要求参数里出现 MotionEvent/InputEvent。
                Class<?>[] ps = m.getParameterTypes();
                boolean carries = false;
                for (Class<?> p : ps) {
                    if (MotionEvent.class.isAssignableFrom(p) || InputEvent.class.isAssignableFrom(p)) {
                        carries = true;
                        break;
                    }
                }
                if (!carries) {
                    continue;
                }
                if (!isTouchEntryName(m.getName())) {
                    continue;
                }
                try {
                    m.setAccessible(true);
                } catch (Throwable ignored) {
                }
                out.add(m);
            }
            c = c.getSuperclass();
        }
        return new ArrayList<>(out);
    }

    /** 只接受由 ROM/SystemUI 自己声明的类（排除 android.* / java.* / androidx.* / kotlin.*）。 */
    static boolean isRomDeclared(Class<?> declaringClass) {
        if (declaringClass == null) {
            return false;
        }
        String name = declaringClass.getName();
        return !(name.startsWith("android.")
                || name.startsWith("java.")
                || name.startsWith("javax.")
                || name.startsWith("androidx.")
                || name.startsWith("kotlin.")
                || name.startsWith("dalvik."));
    }

    /**
     * 触摸入口方法名候选。注意：这只是"候选集合"，不是硬编码依赖：
     * 找不到时上层会记录日志并降级，不会崩。
     */
    static boolean isTouchEntryName(String name) {
        if (name == null) {
            return false;
        }
        return "onMotionEvent".equals(name)
                || "onInputEvent".equals(name)
                || "onTouchEvent".equals(name)
                || "onInterceptTouchEvent".equals(name)
                || "handleMotionEvent".equals(name)
                || "handleTouchEvent".equals(name)
                || "dispatchTouchEvent".equals(name)
                || "interceptMotionEvent".equals(name);
    }

    /** 在类里列出所有方法签名（用于日志确认版本差异）。 */
    static String listMethods(Class<?> cls) {
        StringBuilder sb = new StringBuilder();
        try {
            Class<?> c = cls;
            int guard = 0;
            while (c != null && c != Object.class && guard++ < 6) {
                Method[] ms;
                try {
                    ms = c.getDeclaredMethods();
                } catch (Throwable t) {
                    break;
                }
                for (Method m : ms) {
                    if (sb.length() > 0) {
                        sb.append(" | ");
                    }
                    sb.append(m.getName()).append('/').append(m.getParameterTypes().length);
                    if (sb.length() > 1500) {
                        sb.append(" ...");
                        return sb.toString();
                    }
                }
                c = c.getSuperclass();
            }
        } catch (Throwable t) {
            // ignore
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 调用辅助（前台应用检测用）
    // ------------------------------------------------------------------

    static Object call(Object target, String name, Object... args) throws Exception {
        if (target == null) {
            throw new IllegalStateException("target == null, method=" + name);
        }
        if (args == null) {
            args = new Object[0];
        }
        Method best = null;
        for (Method m : target.getClass().getMethods()) {
            if (!m.getName().equals(name)) {
                continue;
            }
            if (matches(m.getParameterTypes(), args)) {
                best = m;
                break;
            }
        }
        if (best == null) {
            // 再试一次：接口上声明的（有些 Binder Proxy 只暴露接口方法）
            for (Class<?> itf : target.getClass().getInterfaces()) {
                try {
                    Method m = itf.getMethod(name, typesOf(args));
                    best = m;
                    break;
                } catch (Throwable ignored) {
                }
            }
        }
        if (best == null) {
            throw new NoSuchMethodException(name + "/" + args.length);
        }
        try {
            best.setAccessible(true);
        } catch (Throwable ignored) {
        }
        return best.invoke(target, args);
    }

    private static Class<?>[] typesOf(Object[] args) {
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            types[i] = args[i] == null ? Object.class : args[i].getClass();
        }
        return types;
    }

    private static boolean matches(Class<?>[] types, Object[] args) {
        if (types.length != args.length) {
            return false;
        }
        for (int i = 0; i < types.length; i++) {
            Class<?> p = types[i];
            Object a = args[i];
            if (a == null) {
                if (p.isPrimitive()) {
                    return false;
                }
                continue;
            }
            if (p.isPrimitive()) {
                if (!wrapperOf(p).isInstance(a)) {
                    return false;
                }
            } else if (!p.isInstance(a)) {
                return false;
            }
        }
        return true;
    }

    private static Class<?> wrapperOf(Class<?> primitive) {
        if (primitive == int.class) {
            return Integer.class;
        }
        if (primitive == boolean.class) {
            return Boolean.class;
        }
        if (primitive == long.class) {
            return Long.class;
        }
        if (primitive == float.class) {
            return Float.class;
        }
        if (primitive == double.class) {
            return Double.class;
        }
        if (primitive == short.class) {
            return Short.class;
        }
        if (primitive == byte.class) {
            return Byte.class;
        }
        if (primitive == char.class) {
            return Character.class;
        }
        return Void.class;
    }
}
