package com.limelight.pointerengine;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import com.limelight.nvstream.NvConnection;

/**
 * 滚轮钮（M1 · A 线 · remote_desk fork）
 *
 * 可拖动胶囊钮（位置横竖屏各存记忆，UiPrefs "wheel"），语义：
 *   按住上下滑 = 垂直滚动 conn.sendMouseHighResScroll(±WHEEL_DELTA)（WHEEL_DELTA=120）
 *   按住左右滑 = 水平滚动 conn.sendMouseHighResHScroll(±WHEEL_DELTA)
 *   单击       = ±1 步滚动：点上半格 = +1 步（向上），下半格 = -1 步（向下）
 *
 * 灵敏度接 UiPrefs 滚速（wheel_scroll_speed，×100，默认 100=1.0）：
 *   每步行程 = 基准 40dp / 滚速，滚速越大越省手指行程。
 *
 * 触控语义：胶囊外事件返回 false 透传；可见性经 UiPrefs 持久化（面板「滚轮」钮切换）。
 */
public final class WheelButton extends View {

    private static final float CAPSULE_W_DP = 28f;
    private static final float CAPSULE_H_DP = 96f;
    private static final float DRAG_SLOP_DP = 12f;      // 超出即进入拖动/滚动，未超出 = 单击
    private static final float BASE_PX_PER_STEP_DP = 40f; // 滚速 1.0 时每步(120)手指行程
    private static final short WHEEL_DELTA = 120;

    private final NvConnection conn;
    private final UiPrefs prefs;

    private final Paint capsuleFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint capsuleStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private boolean visible = true;

    private float capsuleW, capsuleH, slop, baseStepPx;
    private float cx = -1, cy = -1;            // 胶囊左上角
    private boolean draggingPosition = false;
    private float grabDx, grabDy;

    private boolean scrollGesture = false;     // 已进入滚动手势（移动超阈值）
    private boolean horizontalGesture = false; // 该手势按水平解释（首判方向定轴）
    private float acc;                         // 当前轴上的累计行程（px，正向=上/左滚）
    private float stepPx;                      // 该手势每步行程（含滚速）
    private float downX, downY;
    private int pointerId = -1;

    public WheelButton(Context context, NvConnection conn, UiPrefs prefs) {
        super(context);
        this.conn = conn;
        this.prefs = prefs;
        float d = context.getResources().getDisplayMetrics().density;
        capsuleW = CAPSULE_W_DP * d;
        capsuleH = CAPSULE_H_DP * d;
        slop = DRAG_SLOP_DP * d;
        baseStepPx = BASE_PX_PER_STEP_DP * d;
        capsuleFill.setColor(Color.argb(110, 26, 29, 36));
        capsuleStroke.setColor(Color.argb(160, 255, 255, 255));
        capsuleStroke.setStrokeWidth(1.5f);
        arrowPaint.setColor(Color.argb(220, 229, 231, 235));
        arrowPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setColor(Color.argb(160, 229, 231, 235));
        textPaint.setTextSize(9 * d);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    public boolean isVisible() {
        return visible;
    }

    /** 显隐（Game 面板切换；持久化由 Game 写 UiPrefs） */
    public void setVisible(boolean visible) {
        this.visible = visible;
        resetGesture();
        setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible) {
            requestLayout();
        }
        invalidate();
    }

    private void resetGesture() {
        scrollGesture = false;
        horizontalGesture = false;
        acc = 0;
        pointerId = -1;
        draggingPosition = false;
    }

    private boolean isLandscape() {
        return getWidth() > getHeight();
    }

    private void resolvePosition() {
        float w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        if (cx < 0) {
            float[] saved = prefs.getBtnPos("wheel", isLandscape());
            if (saved != null) {
                cx = saved[0];
                cy = saved[1];
            } else {
                // 默认：右缘、竖向中段（避让右下 L/R 与右中把手）
                cx = w - capsuleW - 10 * getResources().getDisplayMetrics().density;
                cy = h * 0.30f;
            }
        }
        cx = Math.max(0, Math.min(w - capsuleW, cx));
        cy = Math.max(0, Math.min(h - capsuleH, cy));
    }

    private RectF capsuleRect() {
        resolvePosition();
        return new RectF(cx, cy, cx + capsuleW, cy + capsuleH);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!visible) return;
        RectF rect = capsuleRect();

        canvas.drawRoundRect(rect, capsuleW / 2, capsuleW / 2, capsuleFill);
        canvas.drawRoundRect(rect, capsuleW / 2, capsuleW / 2, capsuleStroke);

        // 上/下滚箭头（上半格=上滚，下半格=下滚）
        arrowPaint.setTextSize(capsuleW * 0.5f);
        canvas.drawText("▲", rect.centerX(), rect.top + capsuleH * 0.30f, arrowPaint);
        canvas.drawText("▼", rect.centerX(), rect.top + capsuleH * 0.62f, arrowPaint);
        // 左右滑提示
        textPaint.setTextSize(capsuleW * 0.32f);
        canvas.drawText("⇄", rect.centerX(), rect.top + capsuleH * 0.85f, textPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!visible) {
            return false;
        }
        float x = event.getX();
        float y = event.getY();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                RectF rect = capsuleRect();
                if (!rect.contains(x, y)) {
                    return false;   // 胶囊外透传
                }
                pointerId = event.getPointerId(0);
                downX = x;
                downY = y;
                acc = 0;
                // 拖动改位与滚动手势的冲突消解：沿用 L/R 的布局模式约定——
                // isEditMode 时按住 = 拖动改位（不发滚动）；平时按住 = 滚动。
                if (prefs.isEditMode()) {
                    draggingPosition = true;
                    grabDx = x - rect.left;
                    grabDy = y - rect.top;
                }
                invalidate();
                return true;
            }

            case MotionEvent.ACTION_MOVE: {
                if (draggingPosition) {
                    float w = getWidth(), h = getHeight();
                    cx = Math.max(0, Math.min(w - capsuleW, x - grabDx));
                    cy = Math.max(0, Math.min(h - capsuleH, y - grabDy));
                    invalidate();
                    return true;
                }
                if (pointerId < 0) {
                    return false;
                }
                float dx = x - downX;
                float dy = y - downY;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                if (!scrollGesture) {
                    if (dist <= slop) {
                        return true;   // 未超阈值，等单击或继续滑
                    }
                    scrollGesture = true;
                    horizontalGesture = Math.abs(dx) > Math.abs(dy);
                    // 灵敏度：UiPrefs 滚速（×100），滚速越大每步行程越短
                    float speed = Math.max(0.2f, prefs.getWheelScrollSpeed());
                    stepPx = baseStepPx / speed;
                }
                float amount = horizontalGesture ? dx : dy;
                acc += -amount;   // 手指上滑 = 内容向上滚（+WHEEL_DELTA）
                while (Math.abs(acc) >= stepPx) {
                    int notches = (int) (acc / stepPx);
                    if (notches > Short.MAX_VALUE / WHEEL_DELTA) {
                        notches = Short.MAX_VALUE / WHEEL_DELTA;
                    }
                    short delta = (short) (notches * WHEEL_DELTA);
                    if (horizontalGesture) {
                        conn.sendMouseHighResHScroll(delta);
                    } else {
                        conn.sendMouseHighResScroll(delta);
                    }
                    acc -= notches * stepPx;
                }
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (draggingPosition) {
                    prefs.setBtnPos("wheel", isLandscape(), cx, cy);
                    draggingPosition = false;
                    invalidate();
                    return true;
                }
                if (pointerId < 0) {
                    return false;
                }
                float dx = x - downX;
                float dy = y - downY;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                if (!scrollGesture && dist <= slop) {
                    // 单击 = ±1 步：上半格 +1（上滚），下半格 -1（下滚）
                    RectF rect = capsuleRect();
                    short delta = (y < rect.centerY()) ? WHEEL_DELTA : (short) -WHEEL_DELTA;
                    conn.sendMouseHighResScroll(delta);
                }
                resetGesture();
                invalidate();
                return true;
            }

            default:
                return pointerId >= 0;
        }
    }
}
