package com.limelight.pointerengine;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 虚拟鼠标 UI 参数（M1 Increment 2 · remote_desk fork；Increment 3 扩：滚轮钮/自动解锁/文件发送）
 *
 * 横/竖屏各存一套位置记忆；mode: 0=虚拟鼠标 1=触屏直连。
 * 全部走独立 SharedPreferences（不侵入上游配置类）。
 */
public final class UiPrefs {

    private static final String PREFS_FILE = "remote_desk_mouse_ui";
    private static final String KEY_MODE = "mode";                  // 0=虚拟鼠标 1=触屏直连
    private static final String KEY_LR_VISIBLE = "lr_visible";      // LR 是否显示
    private static final String KEY_EDIT_MODE = "edit_mode";        // 布局调整模式（拖动按钮）
    private static final String KEY_WHEEL_VISIBLE = "wheel_visible";      // 滚轮钮是否显示
    private static final String KEY_WHEEL_SPEED = "wheel_scroll_speed";   // 滚轮灵敏度 ×100（100=1.0）
    private static final String KEY_AUTO_UNLOCK = "auto_unlock_enabled";  // 连接后自动解锁开关
    private static final String KEY_UNLOCK_DELAY_MS = "unlock_delay_ms";  // CAD 后延时毫秒
    private static final String KEY_FILEBRIDGE_HOST = "filebridge_host";  // 文件发送目标（VPS 地址）

    public static final int MODE_VIRTUAL_MOUSE = 0;
    public static final int MODE_TOUCHSCREEN = 1;

    private final SharedPreferences sp;

    public UiPrefs(Context context) {
        sp = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);
    }

    public int getMode() {
        return sp.getInt(KEY_MODE, MODE_VIRTUAL_MOUSE);
    }

    public void setMode(int mode) {
        sp.edit().putInt(KEY_MODE, mode).apply();
    }

    public boolean isLrVisible() {
        return sp.getBoolean(KEY_LR_VISIBLE, true);
    }

    public void setLrVisible(boolean visible) {
        sp.edit().putBoolean(KEY_LR_VISIBLE, visible).apply();
    }

    public boolean isEditMode() {
        return sp.getBoolean(KEY_EDIT_MODE, false);
    }

    public void setEditMode(boolean edit) {
        sp.edit().putBoolean(KEY_EDIT_MODE, edit).apply();
    }

    /** 单钮位置（which="l"/"r"，横/竖屏各一份）：返回 null = 使用默认位置 */
    public float[] getBtnPos(String which, boolean landscape) {
        String k = which + (landscape ? "_land" : "_port");
        String v = sp.getString(k, null);
        if (v == null) return null;
        String[] parts = v.split(",");
        if (parts.length != 2) return null;
        try {
            return new float[]{Float.parseFloat(parts[0]), Float.parseFloat(parts[1])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public void setBtnPos(String which, boolean landscape, float x, float y) {
        String k = which + (landscape ? "_land" : "_port");
        sp.edit().putString(k, x + "," + y).apply();
    }

    /** 清空布局记忆（恢复默认位置；含滚轮钮），并返回是否发生了清理 */
    public boolean resetLayout() {
        boolean had = false;
        for (String w : new String[]{"l", "r", "wheel"}) {
            for (String s : new String[]{"_land", "_port"}) {
                had |= sp.contains(w + s);
                sp.edit().remove(w + s).apply();
            }
        }
        return had;
    }

    /** 把手 Y 位置（占屏高比例 0-1），横竖屏各一份 */
    public float getHandleYRatio(boolean landscape) {
        return sp.getFloat(landscape ? "handle_y_land" : "handle_y_port", 0.5f);
    }

    public void setHandleYRatio(boolean landscape, float ratio) {
        sp.edit().putFloat(landscape ? "handle_y_land" : "handle_y_port", ratio).apply();
    }

    // ---- 滚轮钮（A 线 Increment 3）----

    /** 滚轮钮显隐（持久化，默认开；面板「滚轮」钮切换） */
    public boolean isWheelVisible() {
        return sp.getBoolean(KEY_WHEEL_VISIBLE, true);
    }

    public void setWheelVisible(boolean visible) {
        sp.edit().putBoolean(KEY_WHEEL_VISIBLE, visible).apply();
    }

    /** 滚轮灵敏度 ×100（100 = 1.0；越大每步行程越短） */
    public float getWheelScrollSpeed() {
        return sp.getInt(KEY_WHEEL_SPEED, 100) / 100.0f;
    }

    public void setWheelScrollSpeed(int speedX100) {
        sp.edit().putInt(KEY_WHEEL_SPEED, speedX100).apply();
    }

    // ---- 自动解锁（A 线 Increment 3）----

    /** 串流连接建立后自动发解锁序列 */
    public boolean isAutoUnlockEnabled() {
        return sp.getBoolean(KEY_AUTO_UNLOCK, false);
    }

    public void setAutoUnlockEnabled(boolean enabled) {
        sp.edit().putBoolean(KEY_AUTO_UNLOCK, enabled).apply();
    }

    /** CAD 后到注入密码的延时毫秒（默认 2000） */
    public int getUnlockDelayMs() {
        return sp.getInt(KEY_UNLOCK_DELAY_MS, 2000);
    }

    public void setUnlockDelayMs(int delayMs) {
        sp.edit().putInt(KEY_UNLOCK_DELAY_MS, delayMs).apply();
    }

    // ---- 文件发送（G 线接线参数）----

    /** filebridge 目标地址（VPS 公网地址，首次「发文件」时弹窗录入并记忆） */
    public String getFileBridgeHost() {
        return sp.getString(KEY_FILEBRIDGE_HOST, null);
    }

    public void setFileBridgeHost(String host) {
        sp.edit().putString(KEY_FILEBRIDGE_HOST, host).apply();
    }
}
