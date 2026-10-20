package com.edgeguard.gesture.xposed;

import android.content.Context;
import android.content.res.Resources;
import android.view.MotionEvent;

import com.edgeguard.gesture.common.Logx;

import java.lang.reflect.Field;

/**
 * 边缘判定 / 屏幕宽度。
 *
 * 版本差异（实测）：
 *  - A15 及以前：EdgeBackGestureHandler 有 mEdgeWidthLeft / mEdgeWidthRight / mDisplayWidth；
 *  - A17（本机 HyperOS 37）：仍有 mEdgeWidthLeft / mEdgeWidthRight，但宽度字段变成
 *    mDisplaySize:Point；新架构的触摸入口是匿名 InputEventReceiver / Kotlin lambda，
 *    handler 对象上没有边宽字段（真实对象在 this$0 里），因此这里做了多级兜底。
 *
 * 兜底原则：宁窄勿宽 —— 判定宽度不够最多"漏屏蔽一次"，判定过宽会误伤屏幕中间的正常触摸。
 */
final class EdgeMetrics {

    static final int SIDE_NONE = 0;
    static final int SIDE_LEFT = 1;
    static final int SIDE_RIGHT = 2;

    private static Field fLeft;
    private static Field fRight;
    private static Field fWidth;
    private static Field fDisplaySize;
    private static Field fInsets;

    private static String summary = "(未探测)";
    private static int lastLoggedWidth = -1;

    private EdgeMetrics() {
    }

    static void bind(Class<?> cls) {
        if (cls == null) {
            return;
        }
        fLeft = Reflect.field(cls, "mEdgeWidthLeft", "mLeftEdgeWidth", "mEdgeWidthLeftPx", "mLeftWidth");
        fRight = Reflect.field(cls, "mEdgeWidthRight", "mRightEdgeWidth", "mEdgeWidthRightPx", "mRightWidth");
        fWidth = Reflect.field(cls, "mDisplayWidth", "mScreenWidth", "mDisplayWidthPx");
        fDisplaySize = Reflect.field(cls, "mDisplaySize", "mScreenSize", "displaySize");
        fInsets = Reflect.field(cls, "mBackGestureInsets", "mGestureInsets", "mBackInsets");

        summary = "width=" + fieldName(fWidth)
                + ", displaySize=" + fieldName(fDisplaySize)
                + ", left=" + fieldName(fLeft)
                + ", right=" + fieldName(fRight)
                + ", insets=" + fieldName(fInsets);
        Logx.i("edge metrics: " + summary);
        Logx.i("edge handler fields: " + Reflect.describe(cls));
        Logx.i("edge handler methods: " + Reflect.listMethods(cls));
    }

    static String summary() {
        return summary;
    }

    /**
     * 屏幕宽度：handler 字段 -> Point 字段 -> 外层 this$0（新架构匿名类）-> 系统资源。
     */
    static int displayWidth(Object handler) {
        int width = Reflect.intField(handler, fWidth, 0);
        if (width > 0) {
            return width;
        }
        width = pointWidth(handler, fDisplaySize);
        if (width > 0) {
            return width;
        }
        Object current = handler;
        for (int depth = 0; depth < 3 && current != null; depth++) {
            try {
                Field outerField = Reflect.field(current.getClass(), "this$0");
                if (outerField == null) {
                    break;
                }
                Object outer = outerField.get(current);
                if (outer == null) {
                    break;
                }
                Field pointField = Reflect.field(outer.getClass(),
                        "displaySize", "mDisplaySize", "mScreenSize");
                int w = pointWidth(outer, pointField);
                if (w > 0) {
                    return w;
                }
                current = outer;
            } catch (Throwable t) {
                break;
            }
        }
        return displayWidthPx();
    }

    static int sideOf(Object handler, MotionEvent ev) {
        if (ev == null) {
            return SIDE_NONE;
        }
        int width = displayWidth(handler);
        int left = Reflect.intField(handler, fLeft, 0);
        int right = Reflect.intField(handler, fRight, 0);
        if ((left <= 0 || right <= 0) && fInsets != null) {
            int[] insets = insets(handler);
            if (insets != null) {
                if (left <= 0) {
                    left = insets[0];
                }
                if (right <= 0) {
                    right = insets[1];
                }
            }
        }
        float fallback = Math.max(dp(24f), configBackGestureInset());
        if (left <= 0) {
            left = (int) Math.max(fallback, width * 0.04f);
        }
        if (right <= 0) {
            right = (int) Math.max(fallback, width * 0.04f);
        }
        float x = ev.getX();
        int side = SIDE_NONE;
        if (x <= left) {
            side = SIDE_LEFT;
        } else if (x >= width - right) {
            side = SIDE_RIGHT;
        }
        if (width != lastLoggedWidth) {
            lastLoggedWidth = width;
            Logx.i("edge geom: width=" + width + " leftEdge=" + left + " rightEdge=" + right
                    + " (x=" + x + ", side=" + side + ")");
        }
        return side;
    }

    private static int pointWidth(Object target, Field field) {
        if (target == null || field == null) {
            return 0;
        }
        try {
            Object point = field.get(target);
            if (point == null) {
                return 0;
            }
            return Reflect.intField(point, Reflect.field(point.getClass(), "x"), 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int[] insets(Object handler) {
        try {
            Object v = fInsets.get(handler);
            if (v == null) {
                return null;
            }
            Field lf = Reflect.field(v.getClass(), "left");
            Field rf = Reflect.field(v.getClass(), "right");
            return new int[]{Reflect.intField(v, lf, 0), Reflect.intField(v, rf, 0)};
        } catch (Throwable t) {
            return null;
        }
    }

    /** framework 的 config_backGestureInset（系统默认返回手势边宽，通常 18~24dp）。 */
    private static float configBackGestureInset() {
        try {
            int id = Resources.getSystem().getIdentifier("config_backGestureInset", "dimen", "android");
            if (id > 0) {
                return Resources.getSystem().getDimensionPixelSize(id);
            }
        } catch (Throwable ignored) {
        }
        return 0f;
    }

    private static int displayWidthPx() {
        try {
            Context c = HookRuntime.appContext;
            if (c != null) {
                return c.getResources().getDisplayMetrics().widthPixels;
            }
        } catch (Throwable ignored) {
        }
        try {
            return Resources.getSystem().getDisplayMetrics().widthPixels;
        } catch (Throwable t) {
            return 1080;
        }
    }

    private static float dp(float value) {
        try {
            float density = Resources.getSystem().getDisplayMetrics().density;
            if (density <= 0f) {
                density = 3f;
            }
            return value * density;
        } catch (Throwable t) {
            return value * 3f;
        }
    }

    private static String fieldName(Field f) {
        return f == null ? "(none)" : f.getName();
    }
}
