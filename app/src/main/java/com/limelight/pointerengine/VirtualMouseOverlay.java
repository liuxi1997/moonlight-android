package com.limelight.pointerengine;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.MouseButtonPacket;

/**
 * 虚拟鼠标覆盖层 v2（M1 Increment 2 · remote_desk fork）
 *
 * - 可见虚拟指针（跟随 PointerEngine 相对增量）——仅虚拟鼠标模式绘制
 * - L/R 圆钮：默认右下角（横屏并排/竖屏靠上），**布局模式下可拖动**，横竖屏位置各存记忆
 * - 触屏直连模式：本层不绘制任何内容（全透传）
 *
 * 触控语义：
 *   布局模式关：按住 L/R = 对应鼠标键按下，抬手 = 释放（按住+触控板移动 = 拖拽）；其余透传
 *   布局模式开：按住 L/R = 拖动调整位置（不发送鼠标事件），松手落位持久化；其余透传
 */
public final class VirtualMouseOverlay extends View {

    private final NvConnection conn;
    private final UiPrefs prefs;

    private boolean virtualMode = true;

    // L/R 按钮
    private float btnRadius;
    private float btnSpacing;
    private final RectF leftBtnRect = new RectF();
    private final RectF rightBtnRect = new RectF();
    private final Paint btnFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint btnStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint editHint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int activeButton = -1;   // 按住的按钮：-1 无 / 0 左 / 1 右

    // 布局模式拖动
    private int draggingButton = -1;
    private float dragGrabDx, dragGrabDy;
    private float lrLeftX = -1, lrTopY = -1;   // L 按钮左上角（R = L.x + 2r + spacing）

    public VirtualMouseOverlay(Context context, NvConnection conn, UiPrefs prefs) {
        super(context);
        this.conn = conn;
        this.prefs = prefs;
        float d = context.getResources().getDisplayMetrics().density;
        btnRadius = 26 * d;
        btnSpacing = 14 * d;
        btnFill.setColor(Color.argb(90, 40, 120, 255));
        btnStroke.setColor(Color.argb(180, 255, 255, 255));
        btnStroke.setStrokeWidth(2);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(18 * d);
        textPaint.setTextAlign(Paint.Align.CENTER);
        editHint.setColor(Color.argb(200, 255, 220, 80));
        editHint.setTextSize(14 * d);
    }

    /** 模式切换时由 Game 调用：触屏模式下隐藏指针与 LR */
    public void setVirtualMode(boolean virtual) {
        virtualMode = virtual;
        invalidate();
    }

    public boolean isVirtualMode() {
        return virtualMode;
    }

    /** 模式/布局变化后重绘 */
    private boolean isLandscape() {
        return getWidth() > getHeight();
    }

    /** L 按钮左上角：记忆位置或默认右下角（横竖屏分别取默认） */
    private void resolveLrPositions() {
        float w = getWidth(), h = getHeight();
        if (w == 0) return;
        float[] saved = prefs.getLrPos(isLandscape());
        if (saved != null && lrLeftX < 0) {
            lrLeftX = saved[0];
            lrTopY = saved[1];
        }
        if (lrLeftX < 0 || lrTopY < 0) {
            // 默认：右下角并排（R 靠边角，L 在左一钮距）；竖屏整体上移避手势条
            float bottomPad = btnSpacing + (isLandscape() ? btnSpacing : btnSpacing * 4);
            lrTopY = h - btnRadius * 2 - bottomPad;
            lrLeftX = w - btnRadius * 4 - btnSpacing * 2;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!virtualMode) return;
        resolveLrPositions();

        if (prefs.isLrVisible()) {
            layoutButtonRects();
            drawButton(canvas, leftBtnRect, "L", activeButton == 0 || draggingButton == 0);
            drawButton(canvas, rightBtnRect, "R", activeButton == 1 || draggingButton == 1);
        }
        if (prefs.isEditMode() && prefs.isLrVisible()) {
            canvas.drawText("拖动 L/R 调整位置", getWidth() / 2f, getHeight() - btnSpacing * 6, editHint);
        }
    }

    private void layoutButtonRects() {
        float d = lrLeftX, dy = lrTopY;
        leftBtnRect.set(d, dy, d + btnRadius * 2, dy + btnRadius * 2);
        rightBtnRect.set(d + btnRadius * 2 + btnSpacing, dy,
                d + btnRadius * 4 + btnSpacing, dy + btnRadius * 2);
    }

    private void drawButton(Canvas canvas, RectF rect, String label, boolean pressed) {
        btnFill.setColor(Color.argb(pressed ? 160 : 90, 40, 120, 255));
        canvas.drawOval(rect, btnFill);
        canvas.drawOval(rect, btnStroke);
        canvas.drawText(label, rect.centerX(), rect.centerY() + textPaint.getTextSize() / 3, textPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!virtualMode) return false;
                if (prefs.isEditMode() && prefs.isLrVisible()) {
                    if (rightBtnRect.contains(x, y)) {
                        draggingButton = 1;
                        dragGrabDx = x - rightBtnRect.left;
                        dragGrabDy = y - rightBtnRect.top;
                        return true;
                    }
                    if (leftBtnRect.contains(x, y)) {
                        draggingButton = 0;
                        dragGrabDx = x - leftBtnRect.left;
                        dragGrabDy = y - leftBtnRect.top;
                        return true;
                    }
                    return false;
                }
                if (prefs.isLrVisible()) {
                    layoutButtonRects();
                    if (rightBtnRect.contains(x, y)) {
                        activeButton = 1;
                        conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT);
                        invalidate();
                        return true;
                    }
                    if (leftBtnRect.contains(x, y)) {
                        activeButton = 0;
                        conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
                        invalidate();
                        return true;
                    }
                }
                return false; // 非按钮区域透传（触控板）

            case MotionEvent.ACTION_MOVE:
                if (draggingButton >= 0) {
                    float nx = x - dragGrabDx;
                    float ny = y - dragGrabDy;
                    nx = Math.max(0, Math.min(getWidth() - btnRadius * 4 - btnSpacing, nx));
                    ny = Math.max(0, Math.min(getHeight() - btnRadius * 2 - btnSpacing, ny));
                    lrLeftX = nx;
                    lrTopY = ny;
                    layoutButtonRects();
                    prefs.setLrPos(isLandscape(), lrLeftX, lrTopY);
                    invalidate();
                    return true;
                }
                return activeButton != -1;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (draggingButton >= 0) {
                    draggingButton = -1;
                    invalidate();
                    return true;
                }
                if (activeButton == 0) {
                    conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
                } else if (activeButton == 1) {
                    conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);
                }
                activeButton = -1;
                invalidate();
                return true;

            default:
                return activeButton != -1 || draggingButton >= 0;
        }
    }
}
