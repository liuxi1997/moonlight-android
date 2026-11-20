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

    // 布局模式拖动（L/R 独立位置，各自持久化）
    private int draggingButton = -1;
    private float dragGrabDx, dragGrabDy;
    private float lX = -1, lY = -1;   // L 左上角
    private float rX = -1, rY = -1;   // R 左上角

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

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        boolean land = w > h;
        // 旋转切换时重载该屏向的独立记忆（lX<0 触发 resolve 重新取值）
        if (land != (ow > oh)) {
            lX = lY = rX = rY = -1;
        }
        invalidate();
    }

    /** 解析 L/R 位置：记忆位置或默认（R 贴右下角，L 在其左一钮距；竖屏整体上移避手势条） */
    private void resolveLrPositions() {
        float w = getWidth(), h = getHeight();
        if (w == 0) return;
        boolean land = isLandscape();
        if (rX < 0) {
            float[] saved = prefs.getBtnPos("r", land);
            if (saved != null) {
                rX = saved[0]; rY = saved[1];
            } else {
                float bottomPad = btnSpacing + (land ? btnSpacing : btnSpacing * 4);
                rY = h - btnRadius * 2 - bottomPad;
                rX = w - btnRadius * 2 - btnSpacing;
            }
        }
        if (lX < 0) {
            float[] saved = prefs.getBtnPos("l", land);
            if (saved != null) {
                lX = saved[0]; lY = saved[1];
            } else {
                lY = rY;
                lX = rX - btnRadius * 2 - btnSpacing;
            }
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
            canvas.drawText("分别拖动 L / R 到顺手位置（自动保存）", getWidth() / 2f, getHeight() - btnSpacing * 6, editHint);
        }
    }

    private void layoutButtonRects() {
        leftBtnRect.set(lX, lY, lX + btnRadius * 2, lY + btnRadius * 2);
        rightBtnRect.set(rX, rY, rX + btnRadius * 2, rY + btnRadius * 2);
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
                    float nx = Math.max(0, Math.min(getWidth() - btnRadius * 2, x - dragGrabDx));
                    float ny = Math.max(0, Math.min(getHeight() - btnRadius * 2, y - dragGrabDy));
                    if (draggingButton == 0) { lX = nx; lY = ny; }
                    else { rX = nx; rY = ny; }
                    layoutButtonRects();
                    invalidate();
                    return true;
                }
                return activeButton != -1;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (draggingButton >= 0) {
                    // 松手落位并持久化（该按钮、该屏向独立记忆）
                    boolean land = isLandscape();
                    if (draggingButton == 0) prefs.setBtnPos("l", land, lX, lY);
                    else prefs.setBtnPos("r", land, rX, rY);
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
