package io.github.usernameNaN1234.EdgeGestureGuard.common;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * App 侧规则持久化（SharedPreferences 里存整份 JSON，含全局 config）。
 * 写入后由调用方通过 {@link ModuleBroadcasts#pushRules(Context, RuleSet)} 推送给 SystemUI / Launcher。
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

    /** 覆盖某个应用的包级规则（顶部下拉默认关闭，保留旧调用点语义）。 */
    public RuleSet setPackageRule(String packageName, boolean blockLeft, boolean blockRight,
                                  boolean blockBottom) {
        Rule existing = getPackageRule(packageName);
        boolean top = existing != null && existing.blockTopShade;
        return setPackageRule(packageName, blockLeft, blockRight, blockBottom, top);
    }

    /**
     * 覆盖某个应用的包级规则。四项全不勾 => 删除该应用的规则。
     *
     * @return 更新后的完整规则表（调用方拿去广播）
     */
    public RuleSet setPackageRule(String packageName, boolean blockLeft, boolean blockRight,
                                  boolean blockBottom, boolean blockTopShade) {
        RuleSet current = load();
        List<Rule> list = new ArrayList<>();
        for (Rule r : current.all()) {
            if (r.packageName.equals(packageName) && r.isPackageLevel()) {
                continue;
            }
            list.add(r);
        }
        if (blockLeft || blockRight || blockBottom || blockTopShade) {
            list.add(new Rule(packageName, null, blockLeft, blockRight,
                    blockBottom, blockTopShade));
        }
        RuleSet next = RuleSet.of(list, current.config());
        save(next);
        return next;
    }

    /** 只改全局配置（阈值 / 验证模式 / Launcher 开关），规则原样保留。 */
    public RuleSet setConfig(ModuleConfig config) {
        RuleSet next = load().withConfig(config == null ? ModuleConfig.DEFAULT : config);
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
