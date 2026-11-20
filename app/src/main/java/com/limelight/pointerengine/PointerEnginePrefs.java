package com.limelight.pointerengine;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 指针引擎参数（M1 Increment 1 · remote_desk fork）
 *
 * 独立 SharedPreferences（不侵入上游 PreferenceConfiguration，降低 upstream 合并冲突面）。
 * 存储约定：浮点参数 ×100 存 int（SharedPreferences 无 float 持久化的历史坑规避）。
 *
 * 调校 UI（增量 3）落位后由设置页写入；当前可用 adb 调试：
 *   adb shell "am broadcast -a com.limelight.debug.POINTER_PREFS --es sensitivity 130 --es curve 90"
 *   （广播接收器增量 3 再补，现阶段改默认值直接改这里）
 */
public final class PointerEnginePrefs {

    private static final String PREFS_FILE = "remote_desk_pointer";
    private static final String KEY_ENABLED = "pointer_engine_v1";
    private static final String KEY_SENSITIVITY = "sensitivity";      // ×100，100 = 1.0
    private static final String KEY_CURVE_EXP = "curve_exp";          // ×100，100 = 1.0（线性）
    private static final String KEY_SCROLL_SPEED = "scroll_speed";    // ×100，100 = 1.0

    private final boolean enabled;
    private final float sensitivity;
    private final float curveExponent;
    private final float scrollSpeed;

    private PointerEnginePrefs(boolean enabled, float sensitivity, float curveExponent, float scrollSpeed) {
        this.enabled = enabled;
        this.sensitivity = sensitivity;
        this.curveExponent = curveExponent;
        this.scrollSpeed = scrollSpeed;
    }

    public static PointerEnginePrefs load(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);
        return new PointerEnginePrefs(
                sp.getBoolean(KEY_ENABLED, true),
                sp.getInt(KEY_SENSITIVITY, 100) / 100.0f,
                sp.getInt(KEY_CURVE_EXP, 100) / 100.0f,
                sp.getInt(KEY_SCROLL_SPEED, 100) / 100.0f);
    }

    public static boolean isEnabled(Context context) {
        return context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, true);
    }

    public AccelerationCurve newCurve() {
        return new AccelerationCurve(sensitivity, curveExponent);
    }

    public float getScrollSpeed() {
        return scrollSpeed;
    }
}
