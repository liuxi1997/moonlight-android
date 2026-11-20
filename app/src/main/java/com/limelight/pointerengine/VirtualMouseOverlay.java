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
 * 虚拟鼠标覆盖层（M1 · UU 式）
 *
 * 绘制：
 *  - 可见虚拟指针（跟随 PointerEngine 相对增量）
 *  - 屏上 左键/右键 圆钮（按住 = 按下对应鼠标键，抬手 = 释放 → 按住左键+触控板移动 = 拖拽）
 *
 * 触控：仅命中按钮时消费事件，其余区域 return false 透传给串流视图（触控板）。
 * 放置于 Game 视图栈最顶层（addContentView），不触碰渲染管线。
 */
public final class VirtualMouseOverlay extends View {

    public VirtualMouseOverlay(Context context, NvConnection conn) {
        super(context);
        this.conn = conn;
        float d = context.getResources().getDisplayMetrics().density;
        btnRadius = 26 * d;
        cursorSize = 44 * d;
        btnSpacing = 14 * d;
        textPaint.setTextSize(18 * d);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    private final NvConnection conn;

    // 虚拟指针
    private float cursorX = -1, cursorY = -1;
    private final float cursorSize;
    private final Paint cursorFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cursorStroke = new Paint(Paint.ANTI_ALIAS_FLAG);

    // 按钮
    private final float btnRadius;
    private final float btnSpacing;
    private final RectF leftBtnRect = new RectF();
    private final RectF rightBtnRect = new RectF();
    private final Paint btnFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint btnStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int activeButton = -1;   // -1 无 / 0 左 / 1 右

    @Override
    protected void onDraw(Canvas canvas) {
        if (cursorX < 0) return;
        drawButtons(canvas);
        drawCursor(canvas);
    }

    private void drawButtons(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        if (w == 0) return;
        float bx = w - btnRadius * 2 - btnSpacing * 2;
        float by = h - btnRadius * 2 - btnSpacing;
        leftBtnRect.set(bx, by, bx + btnRadius * 2, by + btnRadius * 2);
        rightBtnRect.set(bx + btnRadius * 2 + btnSpacing, by,
                bx + btnRadius * 4 + btnSpacing, by + btnRadius * 2);

        drawButton(canvas, leftBtnRect, "L", activeButton == 0);
        drawButton(canvas, rightBtnRect, "R", activeButton == 1);
    }

    private void drawButton(Canvas canvas, RectF rect, String label, boolean pressed) {
        btnFill.setColor(Color.argb(pressed ? 160 : 90, 40, 120, 255));
        btnStroke.setColor(Color.argb(180, 255, 255, 255));
        btnStroke.setStrokeWidth(2);
        canvas.drawOval(rect, btnFill);
        canvas.drawOval(rect, btnStroke);
        float cy = rect.centerY() + textPaint.getTextSize() / 3;
        canvas.drawText(label, rect.centerX(), cy, textPaint);
    }

    private void drawCursor(Canvas canvas) {
        float s = cursorSize;
        Path arrow = new Path();
        arrow.moveTo(cursorX, cursorY);
        arrow.lineTo(cursorX, cursorY + s * 0.80f);
        arrow.lineTo(cursorX + s * 0.24f, cursorY + s * 0.62f);
        arrow.lineTo(cursorX + s * 0.36f, cursorY + s * 0.90f);
        arrow.lineTo(cursorX + s * 0.46f, cursorY + s * 0.85f);
        arrow.lineTo(cursorX + s * 0.34f, cursorY + s * 0.58f);
        arrow.lineTo(cursorX + s * 0.62f, cursorY + s * 0.58f);
        arrow.close();

        cursorFill.setColor(Color.argb(220, 255, 255, 255));
        cursorStroke.setColor(Color.argb(230, 20, 20, 20));
        cursorStroke.setStrokeWidth(Math.max(2f, s * 0.045f));
        cursorStroke.setStyle(Paint.Style.STROKE);
        canvas.drawPath(arrow, cursorFill);
        canvas.drawPath(arrow, cursorStroke);
        cursorStroke.setStyle(Paint.Style.FILL_AND_STROKE);
    }

    /** PointerEngine.CursorListener 回调入口 */
    public void setCursorPosition(float x, float y) {
        cursorX = x;
        cursorY = y;
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (rightBtnRect.contains(x, y)) {
                    activeButton = 1;
                    conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT);
                    invalidate();
                    return true;
                } else if (leftBtnRect.contains(x, y)) {
                    activeButton = 0;
                    conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
                    invalidate();
                    return true;
                }
                return false; // 非按钮区域透传（触控板）

            case MotionEvent.ACTION_MOVE:
                return activeButton != -1; // 按住期间持续捕获

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (activeButton == 0) {
                    conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
                } else if (activeButton == 1) {
                    conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);
                }
                activeButton = -1;
                invalidate();
                return true;

            default:
                return activeButton != -1;
        }
    }
}
