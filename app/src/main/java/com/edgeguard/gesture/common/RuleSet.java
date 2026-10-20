package com.edgeguard.gesture.common;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 不可变规则表（整体替换，便于在触摸回调里无锁读取）。
 * Hook 进程只读取，App 进程只整体写入。
 */
public final class RuleSet {

    public static final int FORMAT_VERSION = 1;
    public static final RuleSet EMPTY = new RuleSet(Collections.<String, Rule>emptyMap());

    private final Map<String, Rule> byKey;

    private RuleSet(Map<String, Rule> byKey) {
        this.byKey = byKey;
    }

    public static RuleSet of(List<Rule> rules) {
        Map<String, Rule> map = new LinkedHashMap<>();
        if (rules != null) {
            for (Rule r : rules) {
                if (r == null || r.packageName.length() == 0 || !r.blocksAnything()) {
                    continue;
                }
                map.put(r.key(), r);
            }
        }
        return new RuleSet(Collections.unmodifiableMap(map));
    }

    public int size() {
        return byKey.size();
    }

    public List<Rule> all() {
        return new ArrayList<>(byKey.values());
    }

    public List<String> packageNames() {
        List<String> out = new ArrayList<>();
        for (Rule r : byKey.values()) {
            if (!out.contains(r.packageName)) {
                out.add(r.packageName);
            }
        }
        return out;
    }

    public Rule find(String packageName, String activityName) {
        if (packageName == null || packageName.length() == 0) {
            return null;
        }
        if (activityName != null) {
            Rule exact = byKey.get(Rule.key(packageName, activityName));
            if (exact != null) {
                return exact;
            }
        }
        return byKey.get(Rule.key(packageName, null));
    }

    /**
     * 决策入口：Activity 级规则优先，其次包级规则，都没有 -> 不屏蔽（安全默认）。
     */
    public Decision decide(String packageName, String activityName) {
        Rule r = find(packageName, activityName);
        return r == null ? Decision.NONE : new Decision(r.blockLeft, r.blockRight, r.blockBottom);
    }

    public String toJson() {
        try {
            JSONObject root = new JSONObject();
            root.put("version", FORMAT_VERSION);
            JSONArray arr = new JSONArray();
            for (Rule r : byKey.values()) {
                try {
                    arr.put(r.toJson());
                } catch (JSONException ignored) {
                    // 单条失败不影响整表
                }
            }
            root.put("rules", arr);
            return root.toString();
        } catch (Throwable t) {
            Logx.w("RuleSet.toJson failed", t);
            return "{}";
        }
    }

    /** 容错解析：任何异常都返回 EMPTY（= 完全不屏蔽）。 */
    public static RuleSet fromJson(String json) {
        if (json == null || json.length() == 0) {
            return EMPTY;
        }
        try {
            JSONObject root = new JSONObject(json);
            JSONArray arr = root.optJSONArray("rules");
            if (arr == null) {
                return EMPTY;
            }
            List<Rule> list = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                Rule r = Rule.fromJson(arr.optJSONObject(i));
                if (r != null) {
                    list.add(r);
                }
            }
            return of(list);
        } catch (Throwable t) {
            Logx.w("RuleSet.fromJson failed", t);
            return EMPTY;
        }
    }

    @Override
    public String toString() {
        return "RuleSet(" + byKey.size() + ")";
    }

    public static final class Decision {

        public static final Decision NONE = new Decision(false, false, false);

        public final boolean blockLeft;
        public final boolean blockRight;
        public final boolean blockBottom;

        public Decision(boolean blockLeft, boolean blockRight, boolean blockBottom) {
            this.blockLeft = blockLeft;
            this.blockRight = blockRight;
            this.blockBottom = blockBottom;
        }

        public boolean any() {
            return blockLeft || blockRight || blockBottom;
        }
    }
}
