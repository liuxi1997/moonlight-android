package com.limelight.pointerengine;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * 侧边把手 + 快捷面板（M1 Increment 2 · remote_desk fork）
 *
 * - 把手：屏幕右缘半透明圆角条，常驻；Y 位置可拖动（横竖屏各存记忆）
 * - 单击把手 = 展开/收起快捷面板（圆钮从把手向左展开）
 * - 面板钮：[模式]（虚拟鼠标⇄触屏直连）[LR]（显隐）[布局]（进入拖动调整）
 * - 面板外触摸全部透传
 */
public final class HandleOverlay extends View {

    public interface PanelCallback {
        void onToggleMode();   // 切换 虚拟鼠标⇄触屏直连
        void onToggleLr();     // LR 显隐
        void onToggleEdit();   // 进入/退出布局调整
    }

    private static final String[] LABELS = {"模式", "LR", "布局"};

    private final UiPrefs prefs;
    private final PanelCallback callback;

    private final Paint handleFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint btnFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint btnStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF handleRect = new RectF();
    private final RectF[] btnRects = new RectF[LABELS.length];

    private float handleWidth, handleHeight, btnRadius;
    private float handleYRatio = 0.5f;
    private float handleCY = -1;
    private boolean panelOpen = false;
    private boolean draggingHandle = false;
    private float dragStartY = 0;
    private float dragOriginY = 0;
    private boolean isLandscape = false;

    public HandleOverlay(Context context, UiPrefs prefs, PanelCallback callback) {
        super(context);
        this.prefs = prefs;
        this.callback = callback;
        float d = context.getResources().getDisplayMetrics().density;
        handleWidth = 14 * d;
        handleHeight = 96 * d;
        btnRadius = 24 * d;
        handleFill.setColor(Color.argb(80, 200, 200, 200));
        handleStroke.setColor(Color.argb(140, 255, 255, 255));
        handleStroke.setStrokeWidth(1.5f);
        btnFill.setColor(Color.argb(pressed ? 160 : 110, 30, 90, 200));
        btnStroke.setColor(Color.argb(180, 255, 255, 255));
        btnStroke.setStrokeWidth(1.5f);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(12 * d);
        textPaint.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < btnRects.length; i++) btnRects[i] = new RectF();
    }

    private boolean pressed = false;

    public void refreshState() {
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        isLandscape = w > h;
        handleYRatio = prefs.getHandleYRatio(isLandscape);
        handleCY = handleYRatio * h;
        layoutHandle();
    }

    private void layoutHandle() {
        float h = getHeight();
        if (h == 0) return;
        handleCY = Math.max(handleHeight / 2 + btnSpacingSafe(),
                Math.min(h - handleHeight / 2 - btnSpacingSafe(), handleCY));
        handleRect.set(getWidth() - handleWidth, handleCY - handleHeight / 2,
                getWidth(), handleCY + handleHeight / 2);
    }

    private float btnSpacingSafe() {
        return btnRadius / 2;
    }

    private void layoutPanelButtons() {
        // 面板钮从把手左侧向左展开
        float x = handleRect.left - btnSpacingSafe() - btnRadius;
        for (int i = 0; i < btnRects.length; i++) {
            btnRects[i].set(x - btnRadius, handleCY - btnRadius, x + btnRadius, handleCY + btnRadius);
            x -= btnRadius * 2 + 12;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (handleCY < 0) {
            handleCY = handleYRatio * getHeight();
            layoutHandle();
        }
        // 把手
        handleFill.setColor(Color.argb(panelOpen ? 140 : 80, 220, 220, 220));
        canvas.drawRoundRect(handleRect, handleWidth / 2, handleWidth / 2, handleFill);
        canvas.drawRoundRect(handleRect, handleWidth / 2, handleWidth / 2, handleStroke);

        // 面板
        if (panelOpen) {
            layoutPanelButtons();
            boolean virtualMode = prefs.getMode() == UiPrefs.MODE_VIRTUAL_MOUSE;
            String[] states = {
                    virtualMode ? "鼠标" : "触屏",
                    prefs.isLrVisible() ? "LR开" : "LR关",
                    prefs.isEditMode() ? "完成" : "布局"
            };
            for (int i = 0; i < btnRects.length; i++) {
                btnFill.setColor(Color.argb(150, 30, 90, 200));
                canvas.drawCircle(btnRects[i].centerX(), btnRects[i].centerY(), btnRadius, btnFill);
                canvas.drawCircle(btnRects[i].centerX(), btnRects[i].centerY(), btnRadius, btnStroke);
                float ty = btnRects[i].centerY() + textPaint.getTextSize() / 3;
                canvas.drawText(states[i], btnRects[i].centerX(), ty, textPaint);
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (panelOpen) {
                    for (int i = 0; i < btnRects.length; i++) {
                        if (btnRects[i].contains(x, y)) {
                            panelOpen = false;
                            invalidate();
                            dispatchAction(i);
                            return true;
                        }
                    }
                }
                if (handleRect.contains(x, y)) {
                    draggingHandle = true;
                    dragOriginY = y;
                    dragStartY = y;
                    pressed = true;
                    return true;
                }
                return false;

            case MotionEvent.ACTION_MOVE:
                if (draggingHandle) {
                    handleCY = Math.max(handleHeight / 2 + btnSpacingSafe(),
                            Math.min(getHeight() - handleHeight / 2 - btnSpacingSafe(),
                                    handleCY + (y - dragStartY)));
                    dragStartY = y;
                    handleYRatio = handleCY / Math.max(1, getHeight());
                    prefs.setHandleYRatio(isLandscape, handleYRatio);
                    layoutHandle();
                    invalidate();
                    return true;
                }
                return false;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (draggingHandle) {
                    draggingHandle = false;
                    pressed = false;
                    // 只有"无位移的抬起"才算单击展开面板（拖动把手后不弹）
                    if (Math.abs(y - dragOriginY) < 6) {
                        panelOpen = !panelOpen;
                    }
                    invalidate();
                    return true;
                }
                return false;

            default:
                return panelOpen;
        }
    }

    private void dispatchAction(int index) {
        if (callback == null) return;
        switch (index) {
            case 0: callback.onToggleMode(); break;
            case 1: callback.onToggleLr(); break;
            case 2: callback.onToggleEdit(); break;
        }
        invalidate();
    }
}
