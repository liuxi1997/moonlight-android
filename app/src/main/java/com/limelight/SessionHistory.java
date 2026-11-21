package com.limelight;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 会话历史（v1.3 功能追平 · RustDesk 对齐项）：本地记录最近串流会话。
 * 存储：SharedPreferences JSON 数组（最新在前，上限 20 条）。
 * 记录点：Game.onDestroy（串流 Activity 销毁即会话结束）。
 */
public final class SessionHistory {

    public static final class Entry {
        public final String host;
        public final String app;
        public final long endTime;
        public final long durationMs;

        Entry(String host, String app, long endTime, long durationMs) {
            this.host = host;
            this.app = app;
            this.endTime = endTime;
            this.durationMs = durationMs;
        }
    }

    private static final String PREF = "session_history";
    private static final String KEY = "entries";
    private static final int MAX = 20;

    private SessionHistory() {
    }

    /** 记录一次会话（durationMs<=0 视为未真正串流，跳过） */
    public static void record(Context context, String host, String app, long durationMs) {
        if (durationMs <= 0 || host == null || host.isEmpty()) {
            return;
        }
        try {
            SharedPreferences sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            JSONArray arr = new JSONArray(sp.getString(KEY, "[]"));
            JSONObject e = new JSONObject();
            e.put("host", host);
            e.put("app", app == null ? "" : app);
            e.put("end", System.currentTimeMillis());
            e.put("dur", durationMs);
            JSONArray out = new JSONArray();
            out.put(e);
            for (int i = 0; i < arr.length() && out.length() < MAX; i++) {
                out.put(arr.get(i));
            }
            sp.edit().putString(KEY, out.toString()).apply();
        } catch (Exception ignored) {
            // 历史记录失败不影响主流程
        }
    }

    /** 读取全部记录（最新在前）；异常时返回空数组 */
    public static Entry[] load(Context context) {
        try {
            SharedPreferences sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            JSONArray arr = new JSONArray(sp.getString(KEY, "[]"));
            Entry[] out = new Entry[arr.length()];
            for (int i = 0; i < arr.length(); i++) {
                JSONObject e = arr.getJSONObject(i);
                out[i] = new Entry(e.optString("host"), e.optString("app"),
                        e.optLong("end"), e.optLong("dur"));
            }
            return out;
        } catch (Exception e) {
            return new Entry[0];
        }
    }
}
