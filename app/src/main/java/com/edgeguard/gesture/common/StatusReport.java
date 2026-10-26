package com.edgeguard.gesture.common;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Hook 进程 -> App 的状态报告。
 * 用户可以直接在 UI 上看到：到底挂载到了哪个类/方法、底部手势是否可用、
 * 通知栏下拉拦截挂到了哪个类、前台检测走哪一档。
 *
 * 关键原则：**能测到就报数，测不到就说"不可用"**，绝不写"已支持"来安慰用户。
 */
public final class StatusReport {

    public static final String BOTTOM_ATTACHED = "attached";
    public static final String BOTTOM_UNSUPPORTED = "unsupported";
    public static final String BOTTOM_UNKNOWN = "unknown";

    public static final String SHADE_ATTACHED = "attached";
    public static final String SHADE_UNSUPPORTED = "unsupported";
    public static final String SHADE_UNKNOWN = "unknown";

    public boolean edgeHooked;
    public String edgeTarget = "";
    public String edgeMetrics = "";
    public String bottomState = BOTTOM_UNKNOWN;
    public String bottomTarget = "";
    /** 底部坐标判断的实测计数："seen=N blocked=N"（seen=0 即证明这条通道收不到底部事件） */
    public String bottomMeasure = "";
    public String shadeState = SHADE_UNKNOWN;
    public String shadeTarget = "";
    /** Launcher 进程（策略 B）的挂载情况；空 = 没加载到桌面进程（正常） */
    public String launcherState = "";
    public String zoneConfig = "";
    public boolean verifyOnly;
    /** 策略 A 是否真的吞事件（默认 false：只观测，避免吃掉音游底部判定区） */
    public boolean bottomConsume;
    /** 「游戏模式」会话是否开启（顶部下拉 + 底部上滑 的总闸） */
    public boolean armed;
    /** 谁打开的（tile / notification / app / …），用于 UI 显示与排障 */
    public String armedSource = "";
    public String foregroundStrategy = "none";
    public String foregroundError = "";
    public String foregroundPackage = "";
    public String foregroundActivity = "";
    public int ruleCount;
    public long timestamp;
    public String moduleVersion = "1.6.1";
    public final List<String> notes = new ArrayList<>();
    /** Hook 进程的诊断日志尾部（随状态报告送来，App 直接落盘导出） */
    public final List<String> diag = new ArrayList<>();

    public String toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("edgeHooked", edgeHooked);
            o.put("edgeTarget", safe(edgeTarget));
            o.put("edgeMetrics", safe(edgeMetrics));
            o.put("bottomState", bottomState == null ? BOTTOM_UNKNOWN : bottomState);
            o.put("bottomTarget", safe(bottomTarget));
            o.put("bottomMeasure", safe(bottomMeasure));
            o.put("shadeState", shadeState == null ? SHADE_UNKNOWN : shadeState);
            o.put("shadeTarget", safe(shadeTarget));
            o.put("launcherState", safe(launcherState));
            o.put("zoneConfig", safe(zoneConfig));
            o.put("verifyOnly", verifyOnly);
            o.put("bottomConsume", bottomConsume);
            o.put("armed", armed);
            o.put("armedSource", safe(armedSource));
            o.put("foregroundStrategy", foregroundStrategy == null ? "none" : foregroundStrategy);
            o.put("foregroundError", safe(foregroundError));
            o.put("foregroundPackage", safe(foregroundPackage));
            o.put("foregroundActivity", safe(foregroundActivity));
            o.put("ruleCount", ruleCount);
            o.put("timestamp", timestamp);
            o.put("moduleVersion", moduleVersion == null ? "1.0.0" : moduleVersion);
            JSONArray arr = new JSONArray();
            for (String n : notes) {
                arr.put(n);
            }
            o.put("notes", arr);
            JSONArray diagArr = new JSONArray();
            for (String d : diag) {
                diagArr.put(d);
            }
            o.put("diag", diagArr);
            return o.toString();
        } catch (Throwable t) {
            return "{}";
        }
    }

    public static StatusReport fromJson(String json) {
        StatusReport r = new StatusReport();
        if (json == null || json.length() == 0) {
            return r;
        }
        try {
            JSONObject o = new JSONObject(json);
            r.edgeHooked = o.optBoolean("edgeHooked", false);
            r.edgeTarget = o.optString("edgeTarget", "");
            r.edgeMetrics = o.optString("edgeMetrics", "");
            r.bottomState = o.optString("bottomState", BOTTOM_UNKNOWN);
            r.bottomTarget = o.optString("bottomTarget", "");
            r.bottomMeasure = o.optString("bottomMeasure", "");
            r.shadeState = o.optString("shadeState", SHADE_UNKNOWN);
            r.shadeTarget = o.optString("shadeTarget", "");
            r.launcherState = o.optString("launcherState", "");
            r.zoneConfig = o.optString("zoneConfig", "");
            r.verifyOnly = o.optBoolean("verifyOnly", false);
            r.bottomConsume = o.optBoolean("bottomConsume", false);
            r.armed = o.optBoolean("armed", false);
            r.armedSource = o.optString("armedSource", "");
            r.foregroundStrategy = o.optString("foregroundStrategy", "none");
            r.foregroundError = o.optString("foregroundError", "");
            r.foregroundPackage = o.optString("foregroundPackage", "");
            r.foregroundActivity = o.optString("foregroundActivity", "");
            r.ruleCount = o.optInt("ruleCount", 0);
            r.timestamp = o.optLong("timestamp", 0L);
            r.moduleVersion = o.optString("moduleVersion", "1.0.0");
            JSONArray arr = o.optJSONArray("notes");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    String n = arr.optString(i, "");
                    if (n != null && n.length() > 0) {
                        r.notes.add(n);
                    }
                }
            }
            JSONArray diagArr = o.optJSONArray("diag");
            if (diagArr != null) {
                for (int i = 0; i < diagArr.length(); i++) {
                    String d = diagArr.optString(i, "");
                    if (d != null && d.length() > 0) {
                        r.diag.add(d);
                    }
                }
            }
        } catch (Throwable ignored) {
            // 解析失败就当空报告
        }
        return r;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public boolean bottomUsable() {
        return BOTTOM_ATTACHED.equals(bottomState);
    }

    public boolean shadeUsable() {
        return SHADE_ATTACHED.equals(shadeState);
    }

    /**
     * 底部"策略 A"的实测结论。
     *
     * 注意：这里刻意不看 bottomState（探测到的 HomeHandle 入口不等于能拦上滑），
     * 而是看**真实收到过多少底部区域事件**：seen > 0 才有资格说"这条通道能看到底部触摸"。
     */
    public boolean bottomZoneObserved() {
        try {
            String s = bottomMeasure;
            if (s == null || s.length() == 0) {
                return false;
            }
            int i = s.indexOf("seen=");
            if (i < 0) {
                return false;
            }
            int j = i + 5;
            int end = j;
            while (end < s.length() && Character.isDigit(s.charAt(end))) {
                end++;
            }
            if (end == j) {
                return false;
            }
            return Integer.parseInt(s.substring(j, end)) > 0;
        } catch (Throwable t) {
            return false;
        }
    }
}
