package com.edgeguard.gesture.common;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 一条屏蔽规则。
 *
 * 规则键 = 包名 + Activity：
 *  - activityName == null 表示"包级规则"（该应用的所有界面）；
 *  - activityName != null 表示"Activity 级规则"（只对该界面生效，优先级高于包级规则）。
 *
 * 控制器 UI 默认编辑包级规则；Activity 级规则由同一份存储/IPC 结构支持，
 * 后续要加"仅当前 Activity"功能只需在 UI 侧补充写入即可，Hook 端无需改动。
 */
public final class Rule {

    public static final String KEY_ANY_ACTIVITY = "*";

    public final String packageName;
    public final String activityName;
    public final boolean blockLeft;
    public final boolean blockRight;
    public final boolean blockBottom;

    public Rule(String packageName, String activityName,
                boolean blockLeft, boolean blockRight, boolean blockBottom) {
        this.packageName = packageName == null ? "" : packageName;
        String act = activityName;
        this.activityName = (act == null || act.length() == 0) ? null : act;
        this.blockLeft = blockLeft;
        this.blockRight = blockRight;
        this.blockBottom = blockBottom;
    }

    public boolean isPackageLevel() {
        return activityName == null;
    }

    public boolean blocksAnything() {
        return blockLeft || blockRight || blockBottom;
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
                o.optBoolean("bottom", false));
    }

    @Override
    public String toString() {
        return key() + "[l=" + blockLeft + ",r=" + blockRight + ",b=" + blockBottom + "]";
    }
}
