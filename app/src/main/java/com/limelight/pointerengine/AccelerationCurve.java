package com.limelight.pointerengine;

/**
 * 指针加速曲线（M1 Increment 1 · remote_desk fork）
 *
 * 幂曲线简化版 Windows ballistics：
 *   scale(m) = sensitivity * m^(curveExponent) / m  （作用于各轴分量，方向保持不变）
 *   curveExponent = 1.0 → 线性 1:1（仅乘灵敏度）
 *   curveExponent < 1.0 → 慢速移动更精细（UU 手感方向）
 *   curveExponent > 1.0 → 快速移动更飞
 *
 * 纯函数、无状态；参数持久化见 PointerEnginePrefs。
 */
public final class AccelerationCurve {

    private final float sensitivity;
    private final float curveExponent;

    public AccelerationCurve(float sensitivity, float curveExponent) {
        this.sensitivity = Math.max(0.1f, sensitivity);
        this.curveExponent = Math.min(2.0f, Math.max(0.5f, curveExponent));
    }

    /** 由位移幅值算缩放系数（含下限保护，避免原地抖动归零） */
    public float scale(float magnitude) {
        float m = Math.max(1.0f, magnitude);
        return sensitivity * (float) Math.pow(m, curveExponent - 1.0);
    }

    /** 应用曲线：返回 X 轴指针增量 */
    public short applyX(float dx, float dy) {
        return clamp(dx * scale((float) Math.hypot(dx, dy)));
    }

    /** 应用曲线：返回 Y 轴指针增量 */
    public short applyY(float dx, float dy) {
        return clamp(dy * scale((float) Math.hypot(dx, dy)));
    }

    private static short clamp(float v) {
        if (v > Short.MAX_VALUE) return Short.MAX_VALUE;
        if (v < Short.MIN_VALUE) return Short.MIN_VALUE;
        return (short) v;
    }
}
