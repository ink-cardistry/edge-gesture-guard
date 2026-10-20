package com.edgeguard.gesture.common;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * App 侧规则持久化（SharedPreferences 里存整份 JSON）。
 * 写入后由调用方通过 {@link ModuleBroadcasts#pushRules(Context, RuleSet)} 推送给 SystemUI。
 */
public final class RuleStore {

    private final SharedPreferences sp;

    public RuleStore(Context context) {
        Context c = context.getApplicationContext();
        if (c == null) {
            c = context;
        }
        this.sp = c.getSharedPreferences(ModuleContract.PREFS_RULES, Context.MODE_PRIVATE);
    }

    public RuleSet load() {
        try {
            return RuleSet.fromJson(sp.getString(ModuleContract.PREF_RULES_JSON, ""));
        } catch (Throwable t) {
            Logx.w("RuleStore.load failed", t);
            return RuleSet.EMPTY;
        }
    }

    public void save(RuleSet set) {
        try {
            sp.edit().putString(ModuleContract.PREF_RULES_JSON, set == null ? "" : set.toJson()).apply();
        } catch (Throwable t) {
            Logx.w("RuleStore.save failed", t);
        }
    }

    /** 包级规则；没有配置返回 null。 */
    public Rule getPackageRule(String packageName) {
        try {
            return load().find(packageName, null);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 覆盖某个应用的包级规则。三项全不勾 => 删除该应用的规则。
     *
     * @return 更新后的完整规则表（调用方拿去广播）
     */
    public RuleSet setPackageRule(String packageName, boolean blockLeft, boolean blockRight, boolean blockBottom) {
        RuleSet current = load();
        List<Rule> list = new ArrayList<>();
        for (Rule r : current.all()) {
            if (r.packageName.equals(packageName) && r.isPackageLevel()) {
                continue;
            }
            list.add(r);
        }
        if (blockLeft || blockRight || blockBottom) {
            list.add(new Rule(packageName, null, blockLeft, blockRight, blockBottom));
        }
        RuleSet next = RuleSet.of(list);
        save(next);
        return next;
    }

    public List<String> configuredPackages() {
        try {
            return load().packageNames();
        } catch (Throwable t) {
            return new ArrayList<>();
        }
    }
}
