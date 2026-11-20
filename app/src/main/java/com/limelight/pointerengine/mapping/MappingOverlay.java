package com.limelight.pointerengine.mapping;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

import com.limelight.LimeLog;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.KeyboardPacket;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 按键映射覆盖层（M1 · B 线 · remote_desk fork）
 *
 * 按 {@link MappingProfile} 渲染半透明圆形键位钮（视口比例坐标定位），纯 View+Canvas、
 * 零 UI 框架依赖。挂载由 A 线按 contract 执行（Game.java 禁改）：
 *   new MappingOverlay(context, conn, MappingOverlay.loadProfile(this, "assets/mapping/wasd-desktop.json"))
 *   + addContentView(MATCH_PARENT)
 *
 * 触控语义：
 *   可见时：单指落在键位钮上 = 本层消费，抬手（未拖出钮距）发键序列；
 *           落在空白区 = 返回 false 透传（虚拟鼠标/触屏直连照常工作）
 *   不可见时：不绘制、不消费任何事件
 *   双指滚动透传：第二指落下时本层放弃已捕获的按键（不注入任何键）并停止消费该手势。
 *           注：Android 单 View 是整条手势流的唯一 target，无法把已捕获的手势回交下层；
 *           仅当第一指落在空白区时，双指滚动才完整到达 PointerEngine（此为本挂载方式下的最优透传）。
 *
 * 键序列发送（唯一通道，L2）：conn.sendKeyboardInput(VK, KEY_DOWN/KEY_UP, modifiers, 0)；
 * VK 为 Win32 Virtual Key 码（Limelight.h:684：按 US English layout 解释），
 * 直接取自方案 JSON，与物理键盘的 KeyboardTranslator(Android keycode) 通道互不相干。
 */
public final class MappingOverlay extends View {

    private static final String LOG_TAG = "rd_ui";

    /** 点按最大位移（dp），超出按拖走处理不发键 */
    private static final float TAP_SLOP_DP = 16f;

    private final NvConnection conn;
    private final float density;

    private final Paint btnFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint btnStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private MappingProfile profile;
    private boolean visible = true;

    // 单指按键态（-1 = 无捕获）
    private int pressedIndex = -1;
    private int pressedPointerId = -1;
    private float pressDownX, pressDownY;
    // 第二指落下后整条手势放弃消费
    private boolean passThroughGesture = false;

    public MappingOverlay(Context context, NvConnection conn, MappingProfile profile) {
        super(context);
        this.conn = conn;
        this.profile = profile;
        this.density = context.getResources().getDisplayMetrics().density;
        btnFill.setColor(Color.argb(90, 40, 120, 255));
        btnStroke.setColor(Color.argb(180, 255, 255, 255));
        btnStroke.setStrokeWidth(1.5f);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    /** 整体显隐：隐藏时不绘制、不消费事件，并复位进行中的按键 */
    public void setVisible(boolean visible) {
        this.visible = visible;
        if (!visible) {
            resetGesture();
        }
        setVisibility(visible ? View.VISIBLE : View.GONE);
        invalidate();
    }

    public boolean isVisible() {
        return visible;
    }

    /** 替换方案（切换布局/导入新 JSON 后由宿主调用） */
    public void setMappingProfile(MappingProfile profile) {
        this.profile = profile;
        resetGesture();
        invalidate();
    }

    /**
     * 从 assets 读方案 JSON 并解析；失败返回 null（含文件缺失/格式错/无有效键位）。
     *
     * @param assetPath 方案路径，如 "assets/mapping/wasd-desktop.json"
     *                  （容忍 "assets/" 前缀，实际按 AssetManager 相对路径读取）
     */
    public static MappingProfile loadProfile(Context context, String assetPath) {
        if (assetPath == null || assetPath.isEmpty()) {
            LimeLog.warning(LOG_TAG + ": mapping: 空方案路径");
            return null;
        }
        String path = assetPath;
        if (path.startsWith("assets/")) {
            path = path.substring("assets/".length());
        }
        if (path.startsWith("/")) {
            path = path.substring(1);
        }

        String json;
        try (InputStream is = context.getAssets().open(path)) {
            json = readAll(is);
        } catch (IOException e) {
            LimeLog.warning(LOG_TAG + ": mapping: 方案读取失败 " + path + " - " + e);
            return null;
        }

        try {
            MappingProfile profile = parseJson(json);
            if (profile == null) {
                LimeLog.warning(LOG_TAG + ": mapping: 方案无有效键位 " + path);
            }
            return profile;
        } catch (Exception e) {
            LimeLog.warning(LOG_TAG + ": mapping: 方案解析失败 " + path + " - " + e);
            return null;
        }
    }

    /** 解析方案 JSON 文本（包内可见以便测试）：格式错抛 {@link JSONException}；schema 合法但零有效键位返回 null */
    static MappingProfile parseJson(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONObject profileObj = root.getJSONObject("profile");
        String name = profileObj.optString("name", "");
        JSONArray keys = profileObj.getJSONArray("keys");

        List<MappingProfile.KeyDef> out = new ArrayList<>();
        for (int i = 0; i < keys.length(); i++) {
            MappingProfile.KeyDef def = parseKey(keys.getJSONObject(i), i);
            if (def != null) {
                out.add(def);
            }
        }
        if (out.isEmpty()) {
            return null;
        }
        return new MappingProfile(name, out);
    }

    /** 单条键位解析与校验；非法条目告警跳过，不拖垮整个方案 */
    private static MappingProfile.KeyDef parseKey(JSONObject ko, int index) {
        try {
            String id = ko.optString("id", "");
            String label = ko.optString("label", "");
            if (id.isEmpty() || label.isEmpty()) {
                LimeLog.warning(LOG_TAG + ": mapping: 条目#" + index + " 缺 id/label，跳过");
                return null;
            }

            double x = ko.optDouble("x", Double.NaN);
            double y = ko.optDouble("y", Double.NaN);
            double r = ko.optDouble("r", Double.NaN);
            if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(r)
                    || x < 0 || x > 1 || y < 0 || y > 1 || r <= 0 || r > 200) {
                LimeLog.warning(LOG_TAG + ": mapping: 条目#" + index + " x/y/r 越界（x,y∈[0,1]，0<r≤200dp），跳过");
                return null;
            }

            int vk = ko.optInt("vk", 0);
            if (vk < 0 || vk > Short.MAX_VALUE) {
                LimeLog.warning(LOG_TAG + ": mapping: 条目#" + index + " vk 非法，跳过");
                return null;
            }

            int modifiers = ko.optInt("modifiers", 0);
            if (modifiers < 0 || modifiers > 0x0F) {
                LimeLog.warning(LOG_TAG + ": mapping: 条目#" + index + " modifiers 非法（0..0x0F），跳过");
                return null;
            }

            short[] macro = null;
            if (ko.has("macro") && !ko.isNull("macro")) {
                JSONArray ma = ko.getJSONArray("macro");
                List<Short> list = new ArrayList<>();
                for (int i = 0; i < ma.length(); i++) {
                    int m = ma.optInt(i, -1);
                    if (m <= 0 || m > Short.MAX_VALUE) {
                        LimeLog.warning(LOG_TAG + ": mapping: 条目#" + index + " macro[" + i + "] 非法，跳过");
                        return null;
                    }
                    list.add((short) m);
                }
                if (!list.isEmpty()) {
                    macro = new short[list.size()];
                    for (int i = 0; i < list.size(); i++) {
                        macro[i] = list.get(i);
                    }
                }
            }

            if (macro == null && vk == 0) {
                LimeLog.warning(LOG_TAG + ": mapping: 条目#" + index + " 无 vk 且无 macro，跳过");
                return null;
            }

            return new MappingProfile.KeyDef(id, label, (float) x, (float) y, (float) r,
                    (short) vk, modifiers, macro);
        } catch (Exception e) {
            LimeLog.warning(LOG_TAG + ": mapping: 条目#" + index + " 解析异常，跳过 - " + e);
            return null;
        }
    }

    private static String readAll(InputStream is) throws IOException {
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line);
        }
        return sb.toString();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!visible || profile == null) {
            return;
        }
        float w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        List<MappingProfile.KeyDef> keys = profile.getKeys();
        for (int i = 0; i < keys.size(); i++) {
            MappingProfile.KeyDef key = keys.get(i);
            float cx = key.getX() * w;
            float cy = key.getY() * h;
            float radius = key.getRadiusDp() * density;
            boolean pressed = (i == pressedIndex);

            btnFill.setColor(Color.argb(pressed ? 170 : 90, 40, 120, 255));
            canvas.drawCircle(cx, cy, radius, btnFill);
            canvas.drawCircle(cx, cy, radius, btnStroke);

            // 标签字号自适应：长标签（如 Shift/空格）缩小避免溢出圆钮
            String label = key.getLabel();
            float size = radius * (label.length() <= 2 ? 0.62f : 0.38f);
            textPaint.setTextSize(size);
            float ty = cy - (textPaint.descent() + textPaint.ascent()) / 2;
            canvas.drawText(label, cx, ty, textPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!visible || profile == null) {
            return false;
        }
        int actionMasked = event.getActionMasked();
        int pointerCount = event.getPointerCount();

        if (passThroughGesture) {
            // 已放弃的手势：不再消费（含最后一指抬起），全部落空
            if (actionMasked == MotionEvent.ACTION_UP || actionMasked == MotionEvent.ACTION_CANCEL) {
                passThroughGesture = false;
            }
            return false;
        }

        switch (actionMasked) {
            case MotionEvent.ACTION_DOWN: {
                int hit = hitButton(event.getX(), event.getY());
                android.util.Log.d("rd_ui", "mapping DOWN at " + (int) event.getX() + "," + (int) event.getY()
                        + " hitBtn=" + hit);
                if (hit < 0) {
                    return false;   // 空白区透传：虚拟鼠标/触屏直连/双指滚动照常
                }
                pressedIndex = hit;
                pressedPointerId = event.getPointerId(0);
                pressDownX = event.getX();
                pressDownY = event.getY();
                invalidate();
                return true;
            }

            case MotionEvent.ACTION_POINTER_DOWN:
                // 双指：视为滚动/手势意图，放弃已捕获的按键（不发任何键），停止消费该手势
                resetGesture();
                passThroughGesture = true;
                return false;

            case MotionEvent.ACTION_MOVE: {
                if (pressedIndex < 0) {
                    return false;
                }
                int idx = event.findPointerIndex(pressedPointerId);
                if (idx >= 0) {
                    float dx = event.getX(idx) - pressDownX;
                    float dy = event.getY(idx) - pressDownY;
                    if (dx * dx + dy * dy > TAP_SLOP_DP * density * TAP_SLOP_DP * density) {
                        // 拖离点按范围：取消，不发键
                        resetGesture();
                        invalidate();
                    }
                }
                return true;
            }

            case MotionEvent.ACTION_UP:
                if (pressedIndex >= 0) {
                    sendKeySequence(pressedIndex);
                    resetGesture();
                    invalidate();
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                resetGesture();
                invalidate();
                return true;

            default:
                return pressedIndex >= 0;
        }
    }

    /** 命中检测：返回命中的键位下标，无命中返回 -1 */
    private int hitButton(float x, float y) {
        List<MappingProfile.KeyDef> keys = profile.getKeys();
        float w = getWidth(), h = getHeight();
        for (int i = keys.size() - 1; i >= 0; i--) {   // 后画的钮优先（与绘制层级一致）
            MappingProfile.KeyDef key = keys.get(i);
            float dx = x - key.getX() * w;
            float dy = y - key.getY() * h;
            float radius = key.getRadiusDp() * density + density * 4;   // 少量触控余量
            if (dx * dx + dy * dy <= radius * radius) {
                return i;
            }
        }
        return -1;
    }

    /** 发键序列：macro 按序逐键 DOWN→UP；单键 DOWN→UP；修饰掩码附加在每次事件上 */
    private void sendKeySequence(int index) {
        MappingProfile.KeyDef key = profile.getKeys().get(index);
        byte modifier = (byte) key.getModifiers();
        if (key.hasMacro()) {
            for (short vk : key.getMacro()) {
                conn.sendKeyboardInput(vk, KeyboardPacket.KEY_DOWN, modifier, (byte) 0);
                conn.sendKeyboardInput(vk, KeyboardPacket.KEY_UP, modifier, (byte) 0);
            }
        } else if (key.getVk() > 0) {
            conn.sendKeyboardInput(key.getVk(), KeyboardPacket.KEY_DOWN, modifier, (byte) 0);
            conn.sendKeyboardInput(key.getVk(), KeyboardPacket.KEY_UP, modifier, (byte) 0);
        }
    }

    private void resetGesture() {
        pressedIndex = -1;
        pressedPointerId = -1;
        pressDownX = 0;
        pressDownY = 0;
    }
}
