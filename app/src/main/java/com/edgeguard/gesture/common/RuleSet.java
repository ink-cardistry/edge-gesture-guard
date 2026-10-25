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
 * 不可变规则表 + 全局配置（整体替换，便于在触摸回调里无锁读取）。
 * Hook 进程只读取，App 进程只整体写入。
 *
 * 线上格式（version 2）：
 * <pre>
 * {"version":2,
 *  "config":{"top":0.12,"bottom":0.12,"verify":false,"launcher":false},
 *  "rules":[{"package":"com.x","left":true,"right":true,"bottom":false,"top":true}]}
 * </pre>
 * version 1（没有 config）照常解析：config 取默认值。
 * 未知字段一律忽略，因此新老 App / Hook 混装也不会崩。
 */
public final class RuleSet {

    public static final int FORMAT_VERSION = 2;
    public static final RuleSet EMPTY = new RuleSet(Collections.<String, Rule>emptyMap(),
            ModuleConfig.DEFAULT);

    private final Map<String, Rule> byKey;
    private final ModuleConfig config;

    private RuleSet(Map<String, Rule> byKey, ModuleConfig config) {
        this.byKey = byKey;
        this.config = config == null ? ModuleConfig.DEFAULT : config;
    }

    public static RuleSet of(List<Rule> rules) {
        return of(rules, ModuleConfig.DEFAULT);
    }

    public static RuleSet of(List<Rule> rules, ModuleConfig config) {
        Map<String, Rule> map = new LinkedHashMap<>();
        if (rules != null) {
            for (Rule r : rules) {
                if (r == null || r.packageName.length() == 0 || !r.blocksAnything()) {
                    continue;
                }
                map.put(r.key(), r);
            }
        }
        return new RuleSet(Collections.unmodifiableMap(map), config);
    }

    public ModuleConfig config() {
        return config;
    }

    /** 保留全部规则，只换配置（App 侧改阈值/验证模式用）。 */
    public RuleSet withConfig(ModuleConfig newConfig) {
        return new RuleSet(byKey, newConfig);
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
        return r == null ? Decision.NONE
                : new Decision(r.blockLeft, r.blockRight, r.blockBottom, r.blockTopShade);
    }

    public String toJson() {
        try {
            JSONObject root = new JSONObject();
            root.put("version", FORMAT_VERSION);
            config.writeTo(root);
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
            ModuleConfig config = ModuleConfig.fromJson(root.optJSONObject("config"));
            JSONArray arr = root.optJSONArray("rules");
            if (arr == null) {
                return new RuleSet(Collections.<String, Rule>emptyMap(), config);
            }
            List<Rule> list = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                Rule r = Rule.fromJson(arr.optJSONObject(i));
                if (r != null) {
                    list.add(r);
                }
            }
            return of(list, config);
        } catch (Throwable t) {
            Logx.w("RuleSet.fromJson failed", t);
            return EMPTY;
        }
    }

    @Override
    public String toString() {
        return "RuleSet(" + byKey.size() + ", " + config.describe() + ")";
    }

    public static final class Decision {

        public static final Decision NONE = new Decision(false, false, false, false);

        public final boolean blockLeft;
        public final boolean blockRight;
        public final boolean blockBottom;
        public final boolean blockTopShade;

        public Decision(boolean blockLeft, boolean blockRight,
                        boolean blockBottom, boolean blockTopShade) {
            this.blockLeft = blockLeft;
            this.blockRight = blockRight;
            this.blockBottom = blockBottom;
            this.blockTopShade = blockTopShade;
        }

        /** 兼容旧调用点。 */
        public Decision(boolean blockLeft, boolean blockRight, boolean blockBottom) {
            this(blockLeft, blockRight, blockBottom, false);
        }

        public boolean any() {
            return blockLeft || blockRight || blockBottom || blockTopShade;
        }
    }
}
