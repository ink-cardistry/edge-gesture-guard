package io.github.usernameNaN1234.EdgeGestureGuard.common;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 一条屏蔽规则。
 *
 * 规则键 = 包名 + Activity：
 *  - activityName == null 表示"包级规则"（该应用的所有界面）；
 *  - activityName != null 表示"Activity 级规则"（只对该界面生效，优先级高于包级规则）。
 *
 * 四个屏蔽位：
 *  - blockLeft  / blockRight ：左/右边缘返回（A12-A17 均已在 SystemUI 内实现）；
 *  - blockTopShade          ：顶部下拉通知栏（在 SystemUI 的 shade 触摸链上拦截）；
 *  - blockBottom            ：底部上滑（A16/17 的底部手势由 Launcher 执行，
 *                             本模块按"策略 A：坐标区域判断"尽力拦截，可能不可用）。
 *
 * 新增字段全部走 JSON 的可选键（"top" / "bottom"），旧版本规则表照常解析，
 * 缺省 = false（安全默认：不屏蔽）。
 */
public final class Rule {

    public static final String KEY_ANY_ACTIVITY = "*";

    public final String packageName;
    public final String activityName;
    public final boolean blockLeft;
    public final boolean blockRight;
    public final boolean blockBottom;
    public final boolean blockTopShade;

    /** 兼容旧调用点（4 个屏蔽位）：顶部下拉默认关闭。 */
    public Rule(String packageName, String activityName,
                boolean blockLeft, boolean blockRight, boolean blockBottom) {
        this(packageName, activityName, blockLeft, blockRight, blockBottom, false);
    }

    public Rule(String packageName, String activityName,
                boolean blockLeft, boolean blockRight, boolean blockBottom,
                boolean blockTopShade) {
        this.packageName = packageName == null ? "" : packageName;
        String act = activityName;
        this.activityName = (act == null || act.length() == 0) ? null : act;
        this.blockLeft = blockLeft;
        this.blockRight = blockRight;
        this.blockBottom = blockBottom;
        this.blockTopShade = blockTopShade;
    }

    public boolean isPackageLevel() {
        return activityName == null;
    }

    public boolean blocksAnything() {
        return blockLeft || blockRight || blockBottom || blockTopShade;
    }

    public String key() {
        return key(packageName, activityName);
    }

    public static String key(String packageName, String activityName) {
        String pkg = packageName == null ? "" : packageName;
        String act = (activityName == null || activityName.length() == 0) ? KEY_ANY_ACTIVITY : activityName;
        return pkg + "#" + act;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("package", packageName);
        if (activityName != null) {
            o.put("activity", activityName);
        }
        o.put("left", blockLeft);
        o.put("right", blockRight);
        o.put("bottom", blockBottom);
        o.put("top", blockTopShade);
        return o;
    }

    /** 容错解析：任何字段缺失/类型错误都退化为"不屏蔽"。 */
    public static Rule fromJson(JSONObject o) {
        if (o == null) {
            return null;
        }
        String pkg = o.optString("package", "");
        if (pkg.length() == 0) {
            return null;
        }
        String act = o.optString("activity", null);
        if (act != null && (act.length() == 0 || "null".equals(act))) {
            act = null;
        }
        return new Rule(pkg, act,
                o.optBoolean("left", false),
                o.optBoolean("right", false),
                o.optBoolean("bottom", false),
                o.optBoolean("top", false));
    }

    @Override
    public String toString() {
        return key() + "[l=" + blockLeft + ",r=" + blockRight
                + ",b=" + blockBottom + ",t=" + blockTopShade + "]";
    }
}
