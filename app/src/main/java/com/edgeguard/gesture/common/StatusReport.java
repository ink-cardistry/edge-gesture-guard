package com.edgeguard.gesture.common;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Hook 进程 -> App 的状态报告。
 * 用户可以直接在 UI 上看到：到底挂载到了哪个类/方法、底部手势是否可用、前台检测走哪一档。
 */
public final class StatusReport {

    public static final String BOTTOM_ATTACHED = "attached";
    public static final String BOTTOM_UNSUPPORTED = "unsupported";
    public static final String BOTTOM_UNKNOWN = "unknown";

    public boolean edgeHooked;
    public String edgeTarget = "";
    public String edgeMetrics = "";
    public String bottomState = BOTTOM_UNKNOWN;
    public String bottomTarget = "";
    public String foregroundStrategy = "none";
    public String foregroundError = "";
    public String foregroundPackage = "";
    public String foregroundActivity = "";
    public int ruleCount;
    public long timestamp;
    public String moduleVersion = "1.2.0";
    public final List<String> notes = new ArrayList<>();

    public String toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("edgeHooked", edgeHooked);
            o.put("edgeTarget", edgeTarget == null ? "" : edgeTarget);
            o.put("edgeMetrics", edgeMetrics == null ? "" : edgeMetrics);
            o.put("bottomState", bottomState == null ? BOTTOM_UNKNOWN : bottomState);
            o.put("bottomTarget", bottomTarget == null ? "" : bottomTarget);
            o.put("foregroundStrategy", foregroundStrategy == null ? "none" : foregroundStrategy);
            o.put("foregroundError", foregroundError == null ? "" : foregroundError);
            o.put("foregroundPackage", foregroundPackage == null ? "" : foregroundPackage);
            o.put("foregroundActivity", foregroundActivity == null ? "" : foregroundActivity);
            o.put("ruleCount", ruleCount);
            o.put("timestamp", timestamp);
            o.put("moduleVersion", moduleVersion == null ? "1.0.0" : moduleVersion);
            JSONArray arr = new JSONArray();
            for (String n : notes) {
                arr.put(n);
            }
            o.put("notes", arr);
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
        } catch (Throwable ignored) {
            // 解析失败就当空报告
        }
        return r;
    }

    public boolean bottomUsable() {
        return BOTTOM_ATTACHED.equals(bottomState);
    }
}
