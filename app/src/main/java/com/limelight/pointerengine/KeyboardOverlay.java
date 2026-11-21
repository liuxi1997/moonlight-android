package com.limelight.pointerengine;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;

import com.limelight.binding.input.KeyboardTranslator;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.KeyboardPacket;

import java.util.HashMap;
import java.util.Map;

/**
 * 全键盘浮层（M1 · A 线 · remote_desk fork）
 *
 * 布局（贴底 6 行，F1-F12 第二页）：
 *   行0（粘滞+系统）：Shift Ctrl Win Alt | Esc | F页 | ⌫
 *   行1：数字排 1..0
 *   行2-4：字母排 QWERTY 三排
 *   行5：Tab Enter Home End PgUp PgDn ← ↑ ↓ →
 *   F 页：行0 的 F页 钮变为 ABC，行1-2 放 F1-F12
 *
 * 粘滞修饰键（Win/Ctrl/Alt/Shift）：
 *   单击 = 点亮（ARMED）：仅下一击普通键带该修饰位，随后自动清除；
 *   长按 ≥400ms = 锁定（LOCKED）：每击都带该修饰位，再次单击解除。
 *
 * 发送通道（唯一，L2）：conn.sendKeyboardInput(keyMap, KEY_DOWN/KEY_UP, 粘滞位掩码, 0)。
 * keyMap = (0x80 << 8) | Win32 VK —— GFE 协议键码前缀，见 KeyboardTranslator.java:21-23/372
 * （上游注释「GFE's prefix for every key code」）与 moonlight-common-c InputStream.c:949
 * （keyCode 原样 LE16 上行）；切勿直接传裸 VK。
 *
 * 触控语义：面板矩形内事件本层消费；面板外（上层空白）返回 false 透传
 * （虚拟鼠标/触屏直连/双指滚动不受影响）。
 */
public final class KeyboardOverlay extends View {

    // ---- Win32 VK 常量：KeyboardTranslator.VK_* 优先，物理键盘 Android 码翻译通道（translate()）勿混用 ----
    public static final int VK_LSHIFT = 0xA0;      // Win32 VK_LSHIFT
    public static final int VK_LCONTROL = 0xA2;    // Win32 VK_LCONTROL
    public static final int VK_LMENU = 0xA4;       // Win32 VK_LMENU（左 Alt）
    public static final int VK_LWIN = 0x5B;        // Win32 VK_LWIN
    public static final int VK_RETURN = 0x0D;      // Win32 VK_RETURN（translate() 的 KEYCODE_ENTER 同值）
    public static final int VK_DELETE = 0x2E;      // Win32 VK_DELETE（translate() 的 KEYCODE_FORWARD_DEL 同值）

    /** GFE 协议键码：0x80 前缀 + 低字节 Win32 VK（KeyboardTranslator.java:23/372 同式） */
    public static final int KEY_PREFIX = 0x80;

    /** 组装线上 keyMap：高字节 0x80 + 低字节 Win32 VK。所有虚拟键盘发送必须经此封装 */
    public static short vkKeyMap(int vk) {
        return (short) ((KEY_PREFIX << 8) | (vk & 0xFF));
    }

    // ---- 面板几何 ----
    private static final float PAD_DP = 4f;
    private static final float KEY_GAP_DP = 3f;
    private static final float KEY_H_MAX_DP = 46f;
    private static final float PANEL_MAX_RATIO = 0.45f;   // 面板最高占视口比例
    private static final float KEY_RADIUS_DP = 8f;
    private static final long LONG_PRESS_LOCK_MS = 400;

    private static final int TYPE_KEY = 0;    // 普通键：按下/抬起各发一次
    private static final int TYPE_MOD = 1;    // 粘滞修饰键：不单独发 VK，只贡献修饰位
    private static final int TYPE_PAGE = 2;   // F1-12 翻页钮

    private static final int PAGE_MAIN = 0;
    private static final int PAGE_FN = 1;

    /** 粘滞键状态机 */
    private static final int MOD_STATE_NONE = 0;
    private static final int MOD_STATE_ARMED = 1;
    private static final int MOD_STATE_LOCKED = 2;
    private static final int MOD_COUNT = 4;   // Shift Ctrl Alt Win

    private static final class Key {
        final String label;
        final int vk;          // TYPE_KEY 有效；Win32 VK 码
        final float weight;    // 行内宽度权重
        final int type;
        final int modIndex;    // TYPE_MOD 有效：0=Shift 1=Ctrl 2=Alt 3=Win

        Key(String label, int vk, float weight, int type, int modIndex) {
            this.label = label;
            this.vk = vk;
            this.weight = weight;
            this.type = type;
            this.modIndex = modIndex;
        }
    }

    // 键位表：粘滞行 + 各页键行（Win32 VK，US layout）
    private static final Key[] MOD_ROW = {
            new Key("Shift", 0, 1.15f, TYPE_MOD, 0),
            new Key("Ctrl", 0, 1.1f, TYPE_MOD, 1),
            new Key("Win", 0, 1.05f, TYPE_MOD, 3),
            new Key("Alt", 0, 1.05f, TYPE_MOD, 2),
            new Key("Esc", KeyboardTranslator.VK_ESCAPE, 1f, TYPE_KEY, -1),
            new Key("F页", 0, 1.1f, TYPE_PAGE, -1),
            new Key("⌫", KeyboardTranslator.VK_BACK_SPACE, 2.2f, TYPE_KEY, -1),
    };

    private static Key digitKey(char c) {
        return new Key(String.valueOf(c), KeyboardTranslator.VK_0 + (c - '0'), 1f, TYPE_KEY, -1);
    }

    private static Key letterKey(char c) {
        return new Key(String.valueOf(c), KeyboardTranslator.VK_A + (c - 'A'), 1f, TYPE_KEY, -1);
    }

    private static Key fKey(int n) {
        return new Key("F" + n, KeyboardTranslator.VK_F1 + (n - 1), 1f, TYPE_KEY, -1);
    }

    private static final Key[] ROW_DIGITS, ROW_Q, ROW_A, ROW_Z, ROW_NAV, ROW_F1_6, ROW_F7_12;

    static {
        ROW_DIGITS = new Key[]{digitKey('1'), digitKey('2'), digitKey('3'), digitKey('4'), digitKey('5'),
                digitKey('6'), digitKey('7'), digitKey('8'), digitKey('9'), digitKey('0')};
        ROW_Q = new Key[]{letterKey('Q'), letterKey('W'), letterKey('E'), letterKey('R'), letterKey('T'),
                letterKey('Y'), letterKey('U'), letterKey('I'), letterKey('O'), letterKey('P')};
        ROW_A = new Key[]{letterKey('A'), letterKey('S'), letterKey('D'), letterKey('F'), letterKey('G'),
                letterKey('H'), letterKey('J'), letterKey('K'), letterKey('L')};
        ROW_Z = new Key[]{letterKey('Z'), letterKey('X'), letterKey('C'), letterKey('V'), letterKey('B'),
                letterKey('N'), letterKey('M')};
        ROW_NAV = new Key[]{
                new Key("Tab", KeyboardTranslator.VK_TAB, 1.2f, TYPE_KEY, -1),
                new Key("Enter", VK_RETURN, 1.3f, TYPE_KEY, -1),
                new Key("Home", KeyboardTranslator.VK_HOME, 1f, TYPE_KEY, -1),
                new Key("End", KeyboardTranslator.VK_END, 1f, TYPE_KEY, -1),
                new Key("PgUp", KeyboardTranslator.VK_PAGE_UP, 1f, TYPE_KEY, -1),
                new Key("PgDn", KeyboardTranslator.VK_PAGE_DOWN, 1f, TYPE_KEY, -1),
                new Key("←", KeyboardTranslator.VK_LEFT, 1f, TYPE_KEY, -1),
                new Key("↑", KeyboardTranslator.VK_UP, 1f, TYPE_KEY, -1),
                new Key("↓", KeyboardTranslator.VK_DOWN, 1f, TYPE_KEY, -1),
                new Key("→", KeyboardTranslator.VK_RIGHT, 1f, TYPE_KEY, -1),
        };
        ROW_F1_6 = new Key[]{fKey(1), fKey(2), fKey(3), fKey(4), fKey(5), fKey(6)};
        ROW_F7_12 = new Key[]{fKey(7), fKey(8), fKey(9), fKey(10), fKey(11), fKey(12)};
    }

    private final NvConnection conn;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Paint keyFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint keyStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private boolean visible = false;
    private int page = PAGE_MAIN;

    private final int[] modState = new int[MOD_COUNT];
    // 粘滞键长按定时：pointerId -> Runnable
    private final Map<Integer, Runnable> modLongPressJobs = new HashMap<>();

    // 按键手势态：pointerId -> 行列定位（row/col），每键按下时锁定当时生效的修饰掩码
    private final Map<Integer, int[]> pressedPointers = new HashMap<>();
    private final Map<Integer, Byte> pressedModifiers = new HashMap<>();

    private float keyH, panelH, pad, keyGap, keyRadius;
    private float offsetY;             // 面板底部相对屏幕底部的上移量（拖动把手调整，横竖屏各存）
    private boolean draggingKb = false;
    private float dragKbStartY = 0, dragKbOriginY = 0;
    private static final float GRIP_H_DP = 18f;

    private final com.limelight.pointerengine.UiPrefs prefs;

    public KeyboardOverlay(Context context, NvConnection conn, com.limelight.pointerengine.UiPrefs prefs) {
        super(context);
        this.prefs = prefs;
        this.conn = conn;
        float d = context.getResources().getDisplayMetrics().density;
        pad = PAD_DP * d;
        keyGap = KEY_GAP_DP * d;
        keyRadius = KEY_RADIUS_DP * d;
        keyFill.setColor(Color.argb(200, 26, 29, 36));
        keyStroke.setColor(Color.argb(64, 255, 255, 255));   // 玻璃描边 25%，对齐抽屉语言
        keyStroke.setStrokeWidth(1f);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    public boolean isVisible() {
        return visible;
    }

    /** 显隐切换：隐藏时复位粘滞键与进行中的按键（避免悬挂的修饰/按键状态） */
    public void setVisible(boolean visible) {
        android.util.Log.d("rd_ui", "keyboard setVisible=" + visible);
        this.visible = visible;
        if (!visible) {
            resetAll();
        }
        setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible) {
            requestLayout();
        }
        invalidate();
    }

    private void resetAll() {
        for (int i = 0; i < MOD_COUNT; i++) modState[i] = MOD_STATE_NONE;
        for (Runnable job : modLongPressJobs.values()) handler.removeCallbacks(job);
        modLongPressJobs.clear();
        pressedPointers.clear();
        pressedModifiers.clear();
        page = PAGE_MAIN;
    }

    /** 当前生效修饰掩码：LOCKED 全时生效 + ARMED 待消耗 */
    private byte effectiveModifierMask() {
        int mask = 0;
        if (modState[0] != MOD_STATE_NONE) mask |= KeyboardPacket.MODIFIER_SHIFT;
        if (modState[1] != MOD_STATE_NONE) mask |= KeyboardPacket.MODIFIER_CTRL;
        if (modState[2] != MOD_STATE_NONE) mask |= KeyboardPacket.MODIFIER_ALT;
        if (modState[3] != MOD_STATE_NONE) mask |= KeyboardPacket.MODIFIER_META;
        return (byte) mask;
    }

    /** 消耗 ARMED 位（LOCKED 保留）：普通键按下后调用 */
    private void consumeArmedModifiers() {
        for (int i = 0; i < MOD_COUNT; i++) {
            if (modState[i] == MOD_STATE_ARMED) {
                modState[i] = MOD_STATE_NONE;
            }
        }
    }

    // ---- 布局 ----

    private Key[][] pageRows() {
        if (page == PAGE_FN) {
            return new Key[][]{MOD_ROW, ROW_F1_6, ROW_F7_12};
        }
        return new Key[][]{MOD_ROW, ROW_DIGITS, ROW_Q, ROW_A, ROW_Z, ROW_NAV};
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        float d = getResources().getDisplayMetrics().density;
        int rows = pageRows().length;
        float maxPanelH = h * PANEL_MAX_RATIO;
        keyH = Math.min(KEY_H_MAX_DP * d, (maxPanelH - pad * 2 - keyGap * (rows - 1)) / rows);
        panelH = rows * keyH + (rows - 1) * keyGap + pad * 2;
        offsetY = Math.min(prefs.getKbOffsetY(w > h), Math.max(0f, h - panelH));   // 尺寸变化后夹取合法范围
    }

    /** 面板顶部 y（含可拖动偏移；0 偏移时 = getHeight() - panelH） */
    private float panelTop() {
        return getHeight() - panelH - offsetY;
    }

    /** 拖动把手区（面板顶边上方全宽条） */
    private boolean inGrip(float x, float y) {
        float top = panelTop();
        return x >= 0 && x <= getWidth() && y >= top - GRIP_H_DP * getResources().getDisplayMetrics().density
                && y <= top;
    }

    private void layoutKey(int rowIndex, int colIndex, RectF out) {
        Key[] row = pageRows()[rowIndex];
        float weightSum = 0;
        for (Key key : row) weightSum += key.weight;

        float left = pad;
        for (int i = 0; i < colIndex; i++) {
            left += row[i].weight / weightSum * (getWidth() - pad * 2 - (row.length - 1) * keyGap) + keyGap;
        }
        float keyW = row[colIndex].weight / weightSum * (getWidth() - pad * 2 - (row.length - 1) * keyGap);
        float top = panelTop() + pad + rowIndex * (keyH + keyGap);
        out.set(left, top, left + keyW, top + keyH);
    }

    /** 命中检测：返回 {row, col}，未命中返回 null（面板外 = 透传区） */
    private int[] hitKey(float x, float y) {
        if (!visible || x < 0 || x > getWidth() || y < panelTop() || y > panelTop() + panelH) {
            return null;
        }
        float relY = y - panelTop() - pad;
        int row = (int) ((relY + keyGap) / (keyH + keyGap));
        if (row < 0 || row >= pageRows().length) {
            return null;
        }
        Key[] rowKeys = pageRows()[row];
        RectF r = new RectF();
        for (int c = 0; c < rowKeys.length; c++) {
            layoutKey(row, c, r);
            if (r.contains(x, y)) {
                return new int[]{row, c};
            }
        }
        return null;
    }

    // ---- 绘制 ----

    @Override
    protected void onDraw(Canvas canvas) {
        if (!visible) return;
        if (keyH == 0) {
            onSizeChanged(getWidth(), getHeight(), getWidth(), getHeight());
        }

        Key[][] rows = pageRows();
        RectF rect = new RectF();
        for (int r = 0; r < rows.length; r++) {
            for (int c = 0; c < rows[r].length; c++) {
                Key key = rows[r][c];
                layoutKey(r, c, rect);
                drawKey(canvas, rect, key);
            }
        }
    }

    private void drawKey(Canvas canvas, RectF rect, Key key) {
        boolean pressed = isKeyPressed(key);
        int fillAlpha = 200;

        if (key.type == TYPE_MOD) {
            int state = modState[key.modIndex];
            if (state == MOD_STATE_LOCKED) {
                keyFill.setColor(Color.argb(235, 59, 130, 246));   // 锁定：亮蓝
                fillAlpha = 235;
            } else if (state == MOD_STATE_ARMED || pressed) {
                keyFill.setColor(Color.argb(160, 59, 130, 246));   // 点亮/按住：半亮蓝
                fillAlpha = 160;
            } else {
                keyFill.setColor(Color.argb(200, 26, 29, 36));
            }
        } else if (pressed) {
            keyFill.setColor(Color.argb(160, 59, 130, 246));
            fillAlpha = 160;
        } else {
            keyFill.setColor(Color.argb(200, 26, 29, 36));
        }
        keyFill.setAlpha(fillAlpha);

        canvas.drawRoundRect(rect, keyRadius, keyRadius, keyFill);
        canvas.drawRoundRect(rect, keyRadius, keyRadius, keyStroke);

        String label = key.label;
        if (key.type == TYPE_PAGE) {
            label = (page == PAGE_FN) ? "ABC" : "F1-12";
        }
        float size = rect.height() * (label.length() <= 2 ? 0.42f : 0.3f);
        textPaint.setTextSize(size);
        float ty = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2;
        canvas.drawText(label, rect.centerX(), ty, textPaint);

        if (key.type == TYPE_MOD && modState[key.modIndex] == MOD_STATE_LOCKED) {
            // 锁定指示点
            keyFill.setColor(Color.WHITE);
            canvas.drawCircle(rect.centerX(), rect.top + rect.height() * 0.16f,
                    rect.height() * 0.05f, keyFill);
        }
    }

    private boolean isKeyPressed(Key key) {
        Key[][] rows = pageRows();
        for (int[] pos : pressedPointers.values()) {
            if (pos[0] < rows.length && pos[1] < rows[pos[0]].length) {
                Key pressed = rows[pos[0]][pos[1]];
                if (pressed == key) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---- 触控 ----

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!visible) {
            return false;
        }
        int actionMasked = event.getActionMasked();
        if (actionMasked == MotionEvent.ACTION_DOWN) {
            // 拖动把手优先：面板顶边上方全宽条（GONE 态不布局，尺寸以当前 view 为准）
            if (inGrip(event.getX(), event.getY()) && visible) {
                draggingKb = true;
                dragKbStartY = event.getY();
                dragKbOriginY = event.getY();
                return true;
            }
            int[] hit = hitKey(event.getX(), event.getY());
            android.util.Log.d("rd_ui", "kb DOWN at " + (int) event.getX() + "," + (int) event.getY()
                    + " hit=" + (hit == null ? "null" : hit[0] + "," + hit[1])
                    + " size=" + getWidth() + "x" + getHeight() + " panelH=" + panelH + " keyH=" + keyH);
            if (hit == null) {
                return false;   // 面板外透传
            }
            onKeyDown(event.getPointerId(0), hit);
            invalidate();
            return true;
        }

        if (actionMasked == MotionEvent.ACTION_MOVE && draggingKb) {
            float d = getResources().getDisplayMetrics().density;
            float maxUp = getHeight() - panelH - GRIP_H_DP * d;   // 面板顶不低于屏幕顶
            offsetY = Math.max(0f, Math.min(maxUp, offsetY + (dragKbStartY - event.getY())));
            dragKbStartY = event.getY();
            prefs.setKbOffsetY(getWidth() > getHeight(), offsetY);
            invalidate();
            return true;
        }

        if ((actionMasked == MotionEvent.ACTION_UP || actionMasked == MotionEvent.ACTION_CANCEL)
                && draggingKb) {
            draggingKb = false;
            android.util.Log.d("rd_ui", "kb moved offsetY=" + (int) offsetY);
            invalidate();
            return true;
        }

        if (pressedPointers.isEmpty()) {
            return false;
        }

        switch (actionMasked) {
            case MotionEvent.ACTION_POINTER_DOWN: {
                int pointerIndex = event.getActionIndex();
                int[] hit = hitKey(event.getX(pointerIndex), event.getY(pointerIndex));
                if (hit != null) {
                    onKeyDown(event.getPointerId(pointerIndex), hit);
                    invalidate();
                    return true;
                }
                return false;
            }

            case MotionEvent.ACTION_MOVE: {
                boolean handled = false;
                for (int i = 0; i < event.getPointerCount(); i++) {
                    int pointerId = event.getPointerId(i);
                    int[] pos = pressedPointers.get(pointerId);
                    if (pos != null && hitKey(event.getX(i), event.getY(i)) == null) {
                        // 滑出键位：取消该键（不发键/不点亮粘滞键）
                        cancelPointer(pointerId, false);
                        handled = true;
                    }
                }
                if (handled) invalidate();
                return true;
            }

            case MotionEvent.ACTION_POINTER_UP: {
                int pointerIndex = event.getActionIndex();
                int pointerId = event.getPointerId(pointerIndex);
                cancelPointer(pointerId, isOnPressedKey(event, pointerIndex, pointerId));
                invalidate();
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                while (!pressedPointers.isEmpty()) {
                    int pointerId = pressedPointers.keySet().iterator().next();
                    int pointerIndex = event.findPointerIndex(pointerId);
                    cancelPointer(pointerId, isOnPressedKey(event,
                            pointerIndex >= 0 ? pointerIndex : 0, pointerId));
                }
                invalidate();
                return true;

            default:
                return true;
        }
    }

    private void onKeyDown(int pointerId, int[] hit) {
        Key key = pageRows()[hit[0]][hit[1]];
        pressedPointers.put(pointerId, hit);

        if (key.type == TYPE_MOD) {
            scheduleModLongPress(pointerId, key.modIndex);
        } else if (key.type == TYPE_KEY) {
            byte mask = effectiveModifierMask();
            consumeArmedModifiers();
            pressedModifiers.put(pointerId, mask);
            short vk = vkKeyMap(key.vk);
            conn.sendKeyboardInput(vk, KeyboardPacket.KEY_DOWN, mask, (byte) 0);
            android.util.Log.d("rd_ui", "kb send " + key.label + " DOWN vk=0x"
                    + Integer.toHexString(vk & 0xFFFF) + " mask=0x" + Integer.toHexString(mask & 0xFF));
        }
        // TYPE_PAGE：抬起时翻页
    }

    private void onKeyUp(int pointerId, boolean confirmTap) {
        int[] hit = pressedPointers.remove(pointerId);
        if (hit == null) {
            return;
        }
        Key[][] rows = pageRows();
        if (hit[0] >= rows.length || hit[1] >= rows[hit[0]].length) {
            return;   // 翻页后残留的按下态（行列已不属于当前页），直接丢弃
        }
        Key key = rows[hit[0]][hit[1]];

        if (key.type == TYPE_MOD) {
            cancelModLongPress(pointerId);
            // 仅在键上抬指（未滑出）才切换 ARMED ⇄ NONE（LOCKED 时单击 = 解锁）；滑出 = 无副作用取消
            if (confirmTap && modState[key.modIndex] != MOD_STATE_LOCKED) {
                modState[key.modIndex] = (modState[key.modIndex] == MOD_STATE_ARMED)
                        ? MOD_STATE_NONE : MOD_STATE_ARMED;
            }
        } else if (key.type == TYPE_KEY) {
            Byte mask = pressedModifiers.remove(pointerId);
            conn.sendKeyboardInput(vkKeyMap(key.vk), KeyboardPacket.KEY_UP,
                    mask != null ? mask : effectiveModifierMask(), (byte) 0);
        } else if (key.type == TYPE_PAGE) {
            if (confirmTap) {
                page = (page == PAGE_FN) ? PAGE_MAIN : PAGE_FN;
                onSizeChanged(getWidth(), getHeight(), getWidth(), getHeight());
            }
        }
    }

    private void cancelPointer(int pointerId) {
        cancelPointer(pointerId, true);
    }

    private void cancelPointer(int pointerId, boolean confirmTap) {
        if (pressedPointers.containsKey(pointerId)) {
            onKeyUp(pointerId, confirmTap);
        }
    }

    /** 抬指确认：该手指当前是否仍落在自己按下的键上（落在 = 视为单击/正常抬起） */
    private boolean isOnPressedKey(MotionEvent event, int pointerIndex, int pointerId) {
        int[] pos = pressedPointers.get(pointerId);
        if (pos == null) {
            return false;
        }
        int[] hit = hitKey(event.getX(pointerIndex), event.getY(pointerIndex));
        return hit != null && hit[0] == pos[0] && hit[1] == pos[1];
    }

    private void scheduleModLongPress(final int pointerId, final int modIndex) {
        Runnable job = new Runnable() {
            @Override
            public void run() {
                modLongPressJobs.remove(pointerId);
                // 长按 = 锁定（点亮态直接升级为锁定）
                modState[modIndex] = MOD_STATE_LOCKED;
                invalidate();
            }
        };
        modLongPressJobs.put(pointerId, job);
        handler.postDelayed(job, LONG_PRESS_LOCK_MS);
    }

    private void cancelModLongPress(int pointerId) {
        Runnable job = modLongPressJobs.remove(pointerId);
        if (job != null) {
            handler.removeCallbacks(job);
        }
    }
}
