package com.limelight.pointerengine;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import com.limelight.R;

/**
 * 侧边把手 + 快捷面板（M1 Increment 2 · remote_desk fork；v1.2 UI 主题化 by C 线）
 *
 * - 把手：屏幕右缘玻璃胶囊（半透明面板底 + 1.5dp 描边 + 全圆角），常驻；Y 位置可拖动（横竖屏各存记忆）
 * - 单击把手 = 展开/收起快捷面板（胶囊钮从把手向左展开，整体玻璃底）
 * - 面板钮：[模式]（虚拟鼠标⇄触屏直连）[LR]（显隐）[布局]（进入拖动调整）——统一胶囊形 + 图标 + 短标签
 * - 面板外触摸全部透传；层级规则（docs/04 §四）：把手+面板同属一层浮层，全部画在本 View 内
 * - 仅绘制与几何常量归 C 线（UI 主题）；回调与手势分支归 A 线（功能挂载）
 */
public final class HandleOverlay extends View {

    public interface PanelCallback {
        void onToggleMode();   // 切换 虚拟鼠标⇄触屏直连
        void onToggleLr();     // LR 显隐
        void onToggleEdit();   // 进入/退出布局调整
        void onToggleKeyboard();          // 全键盘浮层显隐（A 线）
        void onToggleWheel();             // 滚轮钮显隐（A 线）
        void onToggleMacros();            // 宏面板显隐（A 线）
        void onSendClipboard();           // 剪贴板注入主机（A 线）
        void onUnlock();                  // 自动解锁：CAD+密码序列（A 线）
        void onSwitchDisplay(int delta);  // 多显示器切换：0=弹 N 项选择，±1=循环切（D 线接线）
        void onSendFile();                // 发文件到主机（G 线 FileSendActivity 接线）
    }

    private static final String[] LABELS = {
            "模式", "LR", "布局", "键盘", "滚轮",
            "宏", "粘贴", "解锁", "切屏", "发文件"
    };
    private static final int BUTTONS_PER_ROW = 5;

    /** 外部开关态（键盘/宏面板显隐等瞬态，由 Game 经 setToggleState 回填点亮面板钮） */
    private final boolean[] externalToggle = new boolean[LABELS.length];

    private final UiPrefs prefs;
    private final PanelCallback callback;

    /** 玻璃描边：白 25%（把手/面板统一，docs/04 §四 毛玻璃面板） */
    private static final int GLASS_STROKE = 0x40FFFFFF;

    // —— 设计令牌（res/values/colors.xml，docs/04 §四）——
    private final int cAccent;
    private final int cSurface85;
    private final int cText;
    private final float panelRadius;   // R.dimen.rd_radius_panel
    private final float density;

    private final Paint handleFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint btnFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint btnStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint backdropPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint backdropStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gripPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mousePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mouseWheelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF handleRect = new RectF();
    private final RectF[] btnRects = new RectF[LABELS.length];
    private final RectF panelRect = new RectF();
    private final RectF gripRect = new RectF();
    private final RectF iconMouseRect = new RectF();

    private float handleWidth, handleHeight, btnRadius, btnH;
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
        density = context.getResources().getDisplayMetrics().density;
        // 玻璃胶囊把手（替换原 14dp 矩形条）：全圆角 = 宽度一半
        handleWidth = 40 * density;
        handleHeight = 120 * density;
        btnRadius = 24 * density;      // 胶囊圆角半径（=高度一半），沿用为布局基准
        btnH = getResources().getDimension(R.dimen.rd_touch_target);   // 面板钮高 = 48dp
        panelRadius = getResources().getDimension(R.dimen.rd_radius_panel);
        float strokeW = 1.5f * density;
        // 令牌取色
        cAccent = getResources().getColor(R.color.rd_accent);
        cSurface85 = getResources().getColor(R.color.rd_surface_85);
        cText = getResources().getColor(R.color.rd_text);
        // 把手：半透明面板底 + 1.5dp 玻璃描边
        handleFill.setColor(cSurface85);
        handleStroke.setStyle(Paint.Style.STROKE);
        handleStroke.setStrokeWidth(strokeW);
        handleStroke.setColor(GLASS_STROKE);
        // 面板钮：胶囊（激活=强调色，常态=玻璃）
        btnFill.setStyle(Paint.Style.FILL);
        btnStroke.setStyle(Paint.Style.STROKE);
        btnStroke.setStrokeWidth(strokeW);
        btnStroke.setColor(GLASS_STROKE);
        textPaint.setColor(cText);
        textPaint.setTextSize(13 * density);
        textPaint.setTextAlign(Paint.Align.CENTER);
        // 图标画笔（矢量简笔，Material Symbols 风格）
        iconPaint.setStyle(Paint.Style.STROKE);
        iconPaint.setStrokeCap(Paint.Cap.ROUND);
        iconPaint.setStrokeJoin(Paint.Join.ROUND);
        iconPaint.setStrokeWidth(1.75f * density);
        iconPaint.setColor(cText);
        // 面板整体玻璃底
        backdropPaint.setStyle(Paint.Style.FILL);
        backdropPaint.setColor(cSurface85);
        backdropStroke.setStyle(Paint.Style.STROKE);
        backdropStroke.setStrokeWidth(strokeW);
        backdropStroke.setColor(GLASS_STROKE);
        // 把手呼吸条（拖动提示）
        gripPaint.setStyle(Paint.Style.FILL);
        // 迷你鼠标图标（面板 [LR] 钮）
        mouseWheelPaint.setStyle(Paint.Style.FILL);
        mouseWheelPaint.setColor(cText);
        for (int i = 0; i < btnRects.length; i++) btnRects[i] = new RectF();
    }

    private boolean pressed = false;

    public void refreshState() {
        invalidate();
    }

    /** 回填外部开关态（index 对应 LABELS 下标，如 3=键盘、5=宏面板），点亮/熄灭面板钮 */
    public void setToggleState(int index, boolean on) {
        if (index >= 0 && index < externalToggle.length) {
            externalToggle[index] = on;
            invalidate();
        }
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
        float margin = panelClampMargin();
        handleCY = Math.max(handleHeight / 2 + margin,
                Math.min(h - handleHeight / 2 - margin, handleCY));
        handleRect.set(getWidth() - handleWidth, handleCY - handleHeight / 2,
                getWidth(), handleCY + handleHeight / 2);
    }

    /** 把手可拖动范围钳制边距：两行面板展开时按面板半高（一行钮 + 行距 + 半行钮）预留 */
    private float panelClampMargin() {
        return btnH / 2 + 8 * density + btnH;
    }

    /** 面板胶囊钮两行（各 5 钮）从把手左侧展开：行内右端对齐把手，宽度随「图标+短标签」自适应 */
    private void layoutPanelButtons(String[] labels) {
        float gap = 10 * density;
        float rowGap = 8 * density;
        for (int i = 0; i < btnRects.length; i++) {
            int row = i / BUTTONS_PER_ROW;
            int col = i % BUTTONS_PER_ROW;
            float xRight = handleRect.left - gap;
            for (int k = 0; k < col; k++) {
                xRight -= capsuleWidth(labels[row * BUTTONS_PER_ROW + k]) + gap;
            }
            float w = capsuleWidth(labels[i]);
            float top = (row == 0)
                    ? handleCY - btnH / 2 - rowGap - btnH
                    : handleCY + btnH / 2 + rowGap;
            btnRects[i].set(xRight - w, top, xRight, top + btnH);
        }
    }

    private float capsuleWidth(String label) {
        float pad = 12 * density;
        float icon = 18 * density;
        float iconTextGap = 7 * density;
        return pad + icon + iconTextGap + textPaint.measureText(label) + pad;
    }

    /** 各钮状态短标签：0-4 动态（模式/LR/布局/键盘/滚轮），5（宏）经 setToggleState 回填，6-9 静态 */
    private String[] panelLabels() {
        boolean virtualMode = prefs.getMode() == UiPrefs.MODE_VIRTUAL_MOUSE;
        String[] known = {
                virtualMode ? "鼠标" : "触屏",
                prefs.isLrVisible() ? "LR 开" : "LR 关",
                prefs.isEditMode() ? "完成" : "布局",
                externalToggle[3] ? "键盘开" : "键盘关",
                prefs.isWheelVisible() ? "滚轮开" : "滚轮关",
                externalToggle[5] ? "宏开" : "宏关"
        };
        String[] labels = new String[LABELS.length];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = i < known.length ? known[i] : LABELS[i];
        }
        return labels;
    }

    /** 各钮激活态（点亮=该功能开启，胶囊转强调色） */
    private boolean[] panelActiveStates() {
        boolean[] known = {
                prefs.getMode() == UiPrefs.MODE_VIRTUAL_MOUSE,
                prefs.isLrVisible(),
                prefs.isEditMode(),
                externalToggle[3],
                prefs.isWheelVisible(),
                externalToggle[5]
        };
        boolean[] on = new boolean[LABELS.length];
        for (int i = 0; i < on.length; i++) {
            on[i] = i < known.length && known[i];
        }
        return on;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (handleCY < 0) {
            handleCY = handleYRatio * getHeight();
            layoutHandle();
        }
        drawHandle(canvas);

        // 面板：整体玻璃底 + 胶囊钮（图标+短标签）。
        // 层级规则（docs/04 §四）：把手与面板同属一层浮层，全部画在本 View 内，不新增层。
        if (panelOpen) {
            String[] labels = panelLabels();
            boolean[] on = panelActiveStates();
            layoutPanelButtons(labels);
            drawPanelBackdrop(canvas);
            for (int i = 0; i < btnRects.length; i++) {
                drawPanelButton(canvas, i, labels[i], on[i]);
            }
        }
    }

    /** 把手：玻璃胶囊（半透明面板底 + 1.5dp 描边 + 全圆角），展开态/拖动态转强调色 */
    private void drawHandle(Canvas canvas) {
        handleFill.setColor(panelOpen || draggingHandle ? withAlpha(cAccent, 100) : cSurface85);
        canvas.drawRoundRect(handleRect, handleWidth / 2, handleWidth / 2, handleFill);
        handleStroke.setColor(panelOpen || draggingHandle ? withAlpha(cAccent, 200) : GLASS_STROKE);
        canvas.drawRoundRect(handleRect, handleWidth / 2, handleWidth / 2, handleStroke);
        // 中部呼吸条：暗示可拖动
        float gripW = 4 * density;
        float gripH = 34 * density;
        gripRect.set(handleRect.centerX() - gripW / 2, handleCY - gripH / 2,
                handleRect.centerX() + gripW / 2, handleCY + gripH / 2);
        gripPaint.setColor(withAlpha(cText, draggingHandle ? 220 : 130));
        canvas.drawRoundRect(gripRect, gripW / 2, gripW / 2, gripPaint);
    }

    /** 面板整体玻璃底：包住全部钮（rd_radius_panel 圆角），纯视觉，不参与命中 */
    private void drawPanelBackdrop(Canvas canvas) {
        float l = Float.MAX_VALUE, t = Float.MAX_VALUE, r = -Float.MAX_VALUE, b = -Float.MAX_VALUE;
        for (RectF btn : btnRects) {
            l = Math.min(l, btn.left);
            t = Math.min(t, btn.top);
            r = Math.max(r, btn.right);
            b = Math.max(b, btn.bottom);
        }
        float pad = 10 * density;
        panelRect.set(l - pad, t - pad, r + pad, b + pad);
        canvas.drawRoundRect(panelRect, panelRadius, panelRadius, backdropPaint);
        canvas.drawRoundRect(panelRect, panelRadius, panelRadius, backdropStroke);
    }

    /** 面板钮：统一胶囊形 + 图标 + 短标签；激活态=强调色填充 */
    private void drawPanelButton(Canvas canvas, int index, String label, boolean on) {
        RectF btn = btnRects[index];
        float r = btn.height() / 2;
        btnFill.setColor(on ? withAlpha(cAccent, 210) : cSurface85);
        canvas.drawRoundRect(btn, r, r, btnFill);
        btnStroke.setColor(on ? withAlpha(cAccent, 240) : GLASS_STROKE);
        canvas.drawRoundRect(btn, r, r, btnStroke);
        // 图标 + 短标签
        float iconSize = 18 * density;
        float pad = 12 * density;
        float iconTextGap = 7 * density;
        float iconCx = btn.left + pad + iconSize / 2;
        float cy = btn.centerY();
        drawPanelIcon(canvas, index, iconCx, cy, iconSize);
        float textW = textPaint.measureText(label);
        canvas.drawText(label, btn.left + pad + iconSize + iconTextGap + textW / 2,
                cy + textPaint.getTextSize() / 3, textPaint);
    }

    /** 面板钮图标（Canvas 矢量简笔）：0=模式切换⇄ 1=LR迷你鼠标 2=布局四角括号；其余索引暂无图标 */
    private void drawPanelIcon(Canvas canvas, int index, float cx, float cy, float size) {
        iconPaint.setColor(withAlpha(cText, 235));
        switch (index) {
            case 0: { // 模式：双向箭头 ⇄（虚拟鼠标⇄触屏直连）
                float w = size / 2;
                float y1 = cy - 3.5f * density;
                float y2 = cy + 3.5f * density;
                float ah = 4 * density;
                // 上行箭头（→）
                canvas.drawLine(cx - w, y1, cx + w - ah, y1, iconPaint);
                canvas.drawLine(cx + w - ah, y1, cx + w - ah * 1.6f, y1 - ah, iconPaint);
                canvas.drawLine(cx + w - ah, y1, cx + w - ah * 1.6f, y1 + ah, iconPaint);
                // 下行箭头（←）
                canvas.drawLine(cx + w, y2, cx - w + ah, y2, iconPaint);
                canvas.drawLine(cx - w + ah, y2, cx - w + ah * 1.6f, y2 - ah, iconPaint);
                canvas.drawLine(cx - w + ah, y2, cx - w + ah * 1.6f, y2 + ah, iconPaint);
                break;
            }
            case 1: { // LR：迷你鼠标（左/右分键图形，与 VirtualMouseOverlay 同语言）
                float bw = size * 0.78f;
                float bh = size;
                float rr = bw * 0.42f;
                iconMouseRect.set(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2);
                mousePaint.setStyle(Paint.Style.FILL);
                mousePaint.setColor(withAlpha(cText, 55));
                canvas.drawRoundRect(iconMouseRect, rr, rr, mousePaint);
                mousePaint.setStyle(Paint.Style.STROKE);
                mousePaint.setStrokeWidth(1.25f * density);
                mousePaint.setColor(withAlpha(cText, 235));
                canvas.drawRoundRect(iconMouseRect, rr, rr, mousePaint);
                // 中缝 + 滚轮点
                canvas.drawLine(cx, iconMouseRect.top + 2 * density,
                        cx, iconMouseRect.bottom - 2 * density, mousePaint);
                canvas.drawCircle(cx, iconMouseRect.top + 4.5f * density,
                        1.3f * density, mouseWheelPaint);
                break;
            }
            case 2: { // 布局：四角括号（调整位置）
                float rr = size / 2;
                float a = 5 * density;
                canvas.drawLine(cx - rr, cy - rr, cx - rr, cy - rr + a, iconPaint);
                canvas.drawLine(cx - rr, cy - rr, cx - rr + a, cy - rr, iconPaint);
                canvas.drawLine(cx + rr, cy - rr, cx + rr, cy - rr + a, iconPaint);
                canvas.drawLine(cx + rr, cy - rr, cx + rr - a, cy - rr, iconPaint);
                canvas.drawLine(cx - rr, cy + rr, cx - rr, cy + rr - a, iconPaint);
                canvas.drawLine(cx - rr, cy + rr, cx - rr + a, cy + rr, iconPaint);
                canvas.drawLine(cx + rr, cy + rr, cx + rr, cy + rr - a, iconPaint);
                canvas.drawLine(cx + rr, cy + rr, cx + rr - a, cy + rr, iconPaint);
                break;
            }
            default:
                break; // A 线后续新增钮先以纯短标签呈现
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
                android.util.Log.d("rd_ui", "handle DOWN at " + (int) x + "," + (int) y
                        + " panelOpen=" + panelOpen + " handleRect=" + handleRect);
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
                    float clampMargin = panelClampMargin();
                    handleCY = Math.max(handleHeight / 2 + clampMargin,
                            Math.min(getHeight() - handleHeight / 2 - clampMargin,
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
        android.util.Log.d("rd_ui", "panel action=" + LABELS[index]);
        if (callback == null) return;
        switch (index) {
            case 0: callback.onToggleMode(); break;
            case 1: callback.onToggleLr(); break;
            case 2: callback.onToggleEdit(); break;
            case 3: callback.onToggleKeyboard(); break;
            case 4: callback.onToggleWheel(); break;
            case 5: callback.onToggleMacros(); break;
            case 6: callback.onSendClipboard(); break;
            case 7: callback.onUnlock(); break;
            case 8: callback.onSwitchDisplay(0); break;
            case 9: callback.onSendFile(); break;
        }
        invalidate();
    }
}
