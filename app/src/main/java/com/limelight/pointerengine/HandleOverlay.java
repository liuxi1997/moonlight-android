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
    private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gripPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Path gripPath = new android.graphics.Path();
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
        // 抽屉状态行 / 卡片短标签画笔
        titlePaint.setColor(0xFFFFFFFF);
        titlePaint.setTextSize(14 * density);
        titlePaint.setFakeBoldText(true);
        titlePaint.setTextAlign(Paint.Align.LEFT);
        subPaint.setColor(withAlpha(cText, 165));
        subPaint.setTextSize(12 * density);
        subPaint.setTextAlign(Paint.Align.RIGHT);
        labelPaint.setColor(withAlpha(cText, 235));
        labelPaint.setTextSize(11 * density);
        labelPaint.setTextAlign(Paint.Align.CENTER);
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

    /** 把手可拖动范围钳制边距（面板已改底部抽屉，与把手不冲突，仅防贴边） */
    private float panelClampMargin() {
        return handleHeight / 2 + 16 * density;
    }

    // —— 底部抽屉（UU 式）：状态行 + 两行等宽图标卡片 ——
    private static final float DRAWER_SIDE_PAD = 16f;   // 抽屉距屏幕左右
    private static final float DRAWER_INNER_PAD = 12f;  // 抽屉内边距
    private static final float CARD_H = 58f;            // 图标卡片高
    private static final float CARD_GAP = 10f;          // 卡片间距
    private static final float STATE_H = 30f;           // 状态行高

    private float cardW = 0;

    private float drawerRectBottom() {
        return getHeight() - 20 * density;   // 底部手势条避让
    }

    private float drawerHeight() {
        return DRAWER_INNER_PAD * density * 2 + STATE_H * density
                + CARD_H * density * 2 + CARD_GAP * density;
    }

    private RectF drawerRect = new RectF();

    /** 面板钮两行（各 5 钮）等宽图标卡片，铺在底部抽屉内 */
    private void layoutPanelButtons() {
        float inner = DRAWER_INNER_PAD * density;
        float gap = CARD_GAP * density;
        cardW = (drawerRect.width() - inner * 2 - gap * (BUTTONS_PER_ROW - 1)) / BUTTONS_PER_ROW;
        for (int i = 0; i < btnRects.length; i++) {
            int row = i / BUTTONS_PER_ROW;
            int col = i % BUTTONS_PER_ROW;
            float left = drawerRect.left + inner + col * (cardW + gap);
            float top = drawerRect.top + inner + STATE_H * density + row * (CARD_H * density + gap);
            btnRects[i].set(left, top, left + cardW, top + CARD_H * density);
        }
    }

    /** 抽屉几何：底部弹出，宽度 = 屏宽 - 两侧边距 */
    private void layoutDrawer() {
        float side = DRAWER_SIDE_PAD * density;
        float h = drawerHeight();
        float bottom = drawerRectBottom();
        drawerRect.set(side, bottom - h, getWidth() - side, bottom);
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

        // 面板：UU 式底部抽屉（状态行 + 两行等宽图标卡片）。
        // 层级规则（docs/04 §四）：把手与面板同属一层浮层，全部画在本 View 内，不新增层。
        if (panelOpen) {
            String[] labels = panelLabels();
            boolean[] on = panelActiveStates();
            layoutDrawer();
            layoutPanelButtons();
            drawPanelBackdrop(canvas, labels);
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
        // 上/下小三角（对齐 UU 把手：暗示可拖动方向）
        float tw = 9 * density, th = 6 * density, gap = 5 * density;
        float tcx = handleRect.centerX();
        gripPaint.setColor(withAlpha(cText, draggingHandle ? 230 : 150));
        gripPath.reset();
        gripPath.moveTo(tcx, handleCY - gap - th);
        gripPath.lineTo(tcx - tw / 2, handleCY - gap);
        gripPath.lineTo(tcx + tw / 2, handleCY - gap);
        gripPath.close();
        canvas.drawPath(gripPath, gripPaint);
        gripPath.reset();
        gripPath.moveTo(tcx, handleCY + gap + th);
        gripPath.lineTo(tcx - tw / 2, handleCY + gap);
        gripPath.lineTo(tcx + tw / 2, handleCY + gap);
        gripPath.close();
        canvas.drawPath(gripPath, gripPaint);
    }

    /** 底部抽屉底：更实的暗底（对齐 UU 操作面板）+ 顶部状态行（标题 + 当前模式） */
    private void drawPanelBackdrop(Canvas canvas, String[] labels) {
        // 抽屉底：高不透明深色（串流画面上高可读性，对齐 UU 面板）
        backdropPaint.setColor((cSurface85 & 0x00FFFFFF) | 0xF2000000);
        canvas.drawRoundRect(drawerRect, panelRadius, panelRadius, backdropPaint);
        canvas.drawRoundRect(drawerRect, panelRadius, panelRadius, backdropStroke);
        // 状态行：左「快捷操作」标题 + 右当前操作模式
        float inner = DRAWER_INNER_PAD * density;
        float lineY = drawerRect.top + inner + STATE_H * density / 2 + titlePaint.getTextSize() / 3;
        canvas.drawText("快捷操作", drawerRect.left + inner, lineY, titlePaint);
        boolean virtualMode = prefs.getMode() == UiPrefs.MODE_VIRTUAL_MOUSE;
        canvas.drawText(virtualMode ? "虚拟鼠标" : "触屏直连",
                drawerRect.right - inner, lineY, subPaint);
    }

    /** 面板钮：等宽图标卡片（图标上 / 短标签下，对齐 UU 快捷卡组）；激活态=强调色填充 */
    private void drawPanelButton(Canvas canvas, int index, String label, boolean on) {
        RectF btn = btnRects[index];
        float r = 14 * density;
        btnFill.setColor(on ? withAlpha(cAccent, 225) : 0xE6232A38);
        canvas.drawRoundRect(btn, r, r, btnFill);
        btnStroke.setColor(on ? withAlpha(cAccent, 255) : GLASS_STROKE);
        canvas.drawRoundRect(btn, r, r, btnStroke);
        // 图标（上）+ 短标签（下）
        float iconSize = 22 * density;
        float iconCy = btn.top + btn.height() * 0.38f;
        drawPanelIcon(canvas, index, btn.centerX(), iconCy, iconSize);
        float labelY = btn.top + btn.height() * 0.76f + labelPaint.getTextSize() / 3;
        canvas.drawText(label, btn.centerX(), labelY, labelPaint);
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
            case 3: { // 键盘：圆角矩形 + 键点阵
                float kw = size * 1.35f, kh = size * 0.85f;
                float rr = 2.5f * density;
                RectF kb = new RectF(cx - kw / 2, cy - kh / 2, cx + kw / 2, cy + kh / 2);
                canvas.drawRoundRect(kb, rr, rr, iconPaint);
                float dot = 1.1f * density;
                float step = kw / 6f;
                for (int rI = 0; rI < 2; rI++) {
                    for (int cI = 0; cI < 5; cI++) {
                        canvas.drawCircle(kb.left + step * (cI + 1), kb.top + kh * (0.34f + rI * 0.28f),
                                dot, mouseWheelPaint);
                    }
                }
                mousePaint.setStyle(Paint.Style.STROKE);
                mousePaint.setStrokeWidth(1.25f * density);
                mousePaint.setColor(withAlpha(cText, 235));
                canvas.drawLine(kb.left + step, kb.top + kh * 0.78f, kb.right - step, kb.top + kh * 0.78f, mousePaint);
                break;
            }
            case 4: { // 滚轮：鼠标轮廓 + 中缝轮（滚轮钮）
                float bw = size * 0.72f, bh = size;
                float rr = bw * 0.45f;
                iconMouseRect.set(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2);
                canvas.drawRoundRect(iconMouseRect, rr, rr, iconPaint);
                canvas.drawLine(cx, iconMouseRect.top + 2 * density,
                        cx, iconMouseRect.top + bh * 0.42f, mousePaint);
                break;
            }
            case 5: { // 宏：组合键两方块叠
                float s1 = size * 0.62f;
                RectF b1 = new RectF(cx - size * 0.42f, cy - size * 0.42f,
                        cx - size * 0.42f + s1, cy - size * 0.42f + s1);
                RectF b2 = new RectF(cx - s1 / 2 + 3 * density, cy - s1 / 2 + 3 * density,
                        cx + s1 / 2 + 3 * density, cy + s1 / 2 + 3 * density);
                canvas.drawRoundRect(b1, 2 * density, 2 * density, iconPaint);
                canvas.drawRoundRect(b2, 2 * density, 2 * density, iconPaint);
                break;
            }
            case 6: { // 粘贴：剪贴板（板 + 顶夹）
                float bw = size * 0.92f, bh = size * 1.05f;
                RectF bd = new RectF(cx - bw / 2, cy - bh / 2 + 1.5f * density,
                        cx + bw / 2, cy + bh / 2);
                float rr = 2.5f * density;
                canvas.drawRoundRect(bd, rr, rr, iconPaint);
                RectF clip = new RectF(cx - bw * 0.28f, cy - bh / 2 - 0.5f * density,
                        cx + bw * 0.28f, cy - bh / 2 + 3.5f * density);
                mousePaint.setStyle(Paint.Style.FILL);
                mousePaint.setColor(withAlpha(cText, 235));
                canvas.drawRoundRect(clip, 1.5f * density, 1.5f * density, mousePaint);
                mousePaint.setStyle(Paint.Style.STROKE);
                mousePaint.setStrokeWidth(1.25f * density);
                canvas.drawLine(bd.left + 3 * density, cy + bh * 0.1f, bd.right - 3 * density, cy + bh * 0.1f, mousePaint);
                canvas.drawLine(bd.left + 3 * density, cy + bh * 0.3f, bd.right - 3 * density, cy + bh * 0.3f, mousePaint);
                break;
            }
            case 7: { // 解锁：挂锁（开扣弧）
                float bw = size * 0.85f, bh = size * 0.62f;
                RectF body = new RectF(cx - bw / 2, cy - bh / 2 + 2.5f * density,
                        cx + bw / 2, cy + bh / 2 + 2.5f * density);
                float rr = 2.5f * density;
                canvas.drawRoundRect(body, rr, rr, iconPaint);
                // 开口弧：左半圆弧（未闭合）
                float arcR = bw * 0.30f;
                canvas.drawArc(cx - arcR - 1.5f * density, cy - bh / 2 - arcR * 1.4f,
                        cx - 1.5f * density + arcR * 0.2f, cy - bh / 2 + arcR * 0.4f,
                        180f, 180f, false, iconPaint);
                canvas.drawCircle(cx, cy + 2.5f * density, 1.4f * density, mouseWheelPaint);
                break;
            }
            case 8: { // 切屏：双显示器叠加
                float w1 = size * 1.25f, h1 = size * 0.78f;
                RectF m1 = new RectF(cx - w1 / 2, cy - h1 / 2, cx + w1 / 2, cy + h1 / 2);
                canvas.drawRoundRect(m1, 2 * density, 2 * density, iconPaint);
                canvas.drawLine(cx - w1 * 0.18f, m1.bottom, cx + w1 * 0.18f, m1.bottom, iconPaint);
                // 右上叠一个小屏
                float w2 = w1 * 0.5f, h2 = h1 * 0.5f;
                mousePaint.setStyle(Paint.Style.FILL);
                mousePaint.setColor(withAlpha(cText, 55));
                RectF m2 = new RectF(cx + w1 * 0.16f, cy - h1 / 2 - h2 * 0.55f,
                        cx + w1 * 0.16f + w2, cy - h1 / 2 - h2 * 0.55f + h2);
                canvas.drawRoundRect(m2, 1.5f * density, 1.5f * density, mousePaint);
                canvas.drawRoundRect(m2, 1.5f * density, 1.5f * density, iconPaint);
                break;
            }
            case 9: { // 发文件：文件夹 + 出箭头
                float fw = size * 1.15f, fh = size * 0.82f;
                RectF folder = new RectF(cx - fw / 2, cy - fh / 2 + 1 * density,
                        cx + fw / 2, cy + fh / 2 + 1 * density);
                float rr = 2 * density;
                canvas.drawRoundRect(folder, rr, rr, iconPaint);
                mousePaint.setStyle(Paint.Style.STROKE);
                mousePaint.setStrokeWidth(1.6f * density);
                mousePaint.setColor(withAlpha(cText, 235));
                canvas.drawLine(cx + 1 * density, cy + 1 * density,
                        cx + fw * 0.3f, cy + 1 * density, mousePaint);
                float ah = 2.6f * density;
                canvas.drawLine(cx + fw * 0.3f, cy + 1 * density,
                        cx + fw * 0.3f - ah, cy + 1 * density - ah * 0.7f, mousePaint);
                canvas.drawLine(cx + fw * 0.3f, cy + 1 * density,
                        cx + fw * 0.3f - ah, cy + 1 * density + ah * 0.7f, mousePaint);
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
