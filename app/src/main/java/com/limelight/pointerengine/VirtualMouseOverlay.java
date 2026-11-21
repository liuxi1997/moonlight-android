package com.limelight.pointerengine;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import com.limelight.R;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.MouseButtonPacket;

/**
 * 虚拟鼠标覆盖层 v2（M1 Increment 2 · remote_desk fork；v1.2 UI 主题化 by C 线）
 *
 * - 可见虚拟指针（跟随 PointerEngine 相对增量）——仅虚拟鼠标模式绘制
 * - L/R 圆钮：默认右下角（横屏并排/竖屏靠上），**布局模式下可拖动**，横竖屏位置各存记忆；
 *   v1.2 起 Canvas 矢量鼠标图形替代文字（L=左半键强调 / R=右半键强调），按下态=强调色
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

    /** 玻璃描边：白 25%（与 HandleOverlay 统一，docs/04 §四 毛玻璃面板） */
    private static final int GLASS_STROKE = 0x40FFFFFF;

    // L/R 按钮（绘制侧；命中/拖动逻辑不属本线改动）
    private float btnRadius;
    private float btnSpacing;
    private final RectF leftBtnRect = new RectF();
    private final RectF rightBtnRect = new RectF();
    private final Paint btnFill = new Paint(Paint.ANTI_ALIAS_FLAG);     // 玻璃圆底
    private final Paint btnStroke = new Paint(Paint.ANTI_ALIAS_FLAG);   // 圆底描边
    private final Paint bodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);   // 鼠标机身填充
    private final Paint halfPaint = new Paint(Paint.ANTI_ALIAS_FLAG);   // 半键强调填充
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);   // 机身描边/中缝
    private final Paint wheelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);  // 滚轮
    private final Paint editHint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path halfPath = new Path();
    private final RectF bodyRect = new RectF();
    private final RectF scratchRect = new RectF();
    private final float[] halfRadii = new float[8];

    // —— 设计令牌（res/values/colors.xml，docs/04 §四）——
    private final int cAccent;
    private final int cSurface85;
    private final int cText;
    private final float density;

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
        density = context.getResources().getDisplayMetrics().density;
        btnRadius = 26 * density;
        btnSpacing = 14 * density;
        cAccent = getResources().getColor(R.color.rd_accent);
        cSurface85 = getResources().getColor(R.color.rd_surface_85);
        cText = getResources().getColor(R.color.rd_text);
        btnStroke.setStyle(Paint.Style.STROKE);
        btnStroke.setStrokeWidth(1.5f * density);
        btnStroke.setColor(GLASS_STROKE);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(1.25f * density);
        linePaint.setColor(0x8C3A4152);   // 浅底深灰描边/中缝
        wheelPaint.setStyle(Paint.Style.FILL);
        editHint.setColor(withAlpha(cText, 200));
        editHint.setTextSize(14 * density);
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
            drawMouseButton(canvas, leftBtnRect, 0, activeButton == 0 || draggingButton == 0);
            drawMouseButton(canvas, rightBtnRect, 1, activeButton == 1 || draggingButton == 1);
        }
        if (prefs.isEditMode() && prefs.isLrVisible()) {
            canvas.drawText("分别拖动 L / R 到顺手位置（自动保存）", getWidth() / 2f, getHeight() - btnSpacing * 6, editHint);
        }
    }

    private void layoutButtonRects() {
        leftBtnRect.set(lX, lY, lX + btnRadius * 2, lY + btnRadius * 2);
        rightBtnRect.set(rX, rY, rX + btnRadius * 2, rY + btnRadius * 2);
    }

    /** L/R 钮：玻璃圆底 + 矢量鼠标图形；side 0=左键（左半强调）1=右键（右半强调），按下态=满强调色 */
    private void drawMouseButton(Canvas canvas, RectF btn, int side, boolean pressed) {
        float r = btnRadius;
        float cx = btn.centerX();
        float cy = btn.centerY();
        // 浅色圆钮（对齐 UU：亮底深图形，深浅画面上都醒目）；按下=强调蓝
        btnFill.setColor(pressed ? cAccent : 0xD9EDF0F6);
        canvas.drawCircle(cx, cy, r, btnFill);
        btnStroke.setColor(pressed ? withAlpha(cAccent, 255) : 0x59FFFFFF);
        canvas.drawCircle(cx, cy, r, btnStroke);

        // 鼠标机身（圆角矩形）
        float bw = r * 1.16f;
        float bh = r * 1.5f;
        bodyRect.set(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2);
        float bodyR = bw * 0.42f;
        bodyPaint.setColor(0x663A4152);   // 浅底上的深灰机身
        canvas.drawRoundRect(bodyRect, bodyR, bodyR, bodyPaint);

        // 强调半键：按下=满强调色，常态=45% 强调色提示本钮归属
        scratchRect.set(side == 0 ? bodyRect.left : cx, bodyRect.top,
                side == 0 ? cx : bodyRect.right, bodyRect.bottom);
        setHalfRadii(side == 0, bodyR);
        halfPath.reset();
        halfPath.addRoundRect(scratchRect, halfRadii, Path.Direction.CW);
        halfPaint.setColor(pressed ? 0xFFFFFFFF : 0x803B82F6);   // 按下白示按下，常态蓝示归属
        canvas.drawPath(halfPath, halfPaint);

        // 机身描边 + 中缝 + 滚轮
        linePaint.setColor(0x8C3A4152);   // 浅底深灰描边/中缝
        canvas.drawRoundRect(bodyRect, bodyR, bodyR, linePaint);
        canvas.drawLine(cx, bodyRect.top + 2 * density, cx, bodyRect.bottom - 2 * density, linePaint);
        float ww = bw * 0.14f;
        float wh = bh * 0.2f;
        float wheelTop = bodyRect.top + bh * 0.1f;
        scratchRect.set(cx - ww / 2, wheelTop, cx + ww / 2, wheelTop + wh);
        wheelPaint.setColor(0xE63A4152);   // 浅底深灰滚轮
        canvas.drawRoundRect(scratchRect, ww / 2, ww / 2, wheelPaint);
    }

    /** 半键圆角：外侧跟随机身圆角，内侧（中缝侧）直角 */
    private void setHalfRadii(boolean leftHalf, float r) {
        if (leftHalf) {
            halfRadii[0] = r; halfRadii[1] = r;   // top-left
            halfRadii[2] = 0; halfRadii[3] = 0;   // top-right
            halfRadii[4] = 0; halfRadii[5] = 0;   // bottom-right
            halfRadii[6] = r; halfRadii[7] = r;   // bottom-left
        } else {
            halfRadii[0] = 0; halfRadii[1] = 0;
            halfRadii[2] = r; halfRadii[3] = r;
            halfRadii[4] = r; halfRadii[5] = r;
            halfRadii[6] = 0; halfRadii[7] = 0;
        }
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
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
                    android.util.Log.d("rd_ui", "drop btn=" + draggingButton + " pos=" + (int) lX + "," + (int) lY
                            + "/" + (int) rX + "," + (int) rY + " landscape=" + land);
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
