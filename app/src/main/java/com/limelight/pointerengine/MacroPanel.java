package com.limelight.pointerengine;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.InputType;
import android.view.MotionEvent;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.limelight.binding.input.KeyboardTranslator;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.KeyboardPacket;

/**
 * 快捷键宏面板（M1 · A 线 · remote_desk fork）
 *
 * 顶部悬浮两行胶囊钮：
 *   行0：Alt+Tab / Win+D / Ctrl+C / Ctrl+V
 *   行1：Ctrl+Z / Win+L / 粘贴 / 凭据
 *
 * 发送语义（唯一通道，L2）：逐键 DOWN/UP 带修饰掩码——
 *   conn.sendKeyboardInput(vkKeyMap(VK), KEY_DOWN, 修饰位, 0) → KEY_UP；目标为主机 Sunshine
 *   （GFE 会丢弃带 META 掩码的事件，moonlight-common-c InputStream.c:895-899，本项目不受影响）。
 *
 * 粘贴：读 Android ClipboardManager → conn.sendUtf8Text（Android→PC 单向；PC→Android 属后续）。
 * 凭据：解锁密码增删改查对话框（CredentialVault）+ 自动解锁开关 + 延时可配。
 *
 * 触控语义：面板矩形内本层消费，面板外返回 false 透传。
 */
public final class MacroPanel extends View {

    /** 宏定义：单目标键 + 修饰掩码 */
    private static final class Macro {
        final String label;
        final int vk;
        final byte modifier;

        Macro(String label, int vk, byte modifier) {
            this.label = label;
            this.vk = vk;
            this.modifier = modifier;
        }
    }

    private static final Macro[] MACROS = {
            new Macro("Alt+Tab", KeyboardTranslator.VK_TAB, KeyboardPacket.MODIFIER_ALT),
            new Macro("Win+D", 'D', KeyboardPacket.MODIFIER_META),
            new Macro("Ctrl+C", 'C', KeyboardPacket.MODIFIER_CTRL),
            new Macro("Ctrl+V", 'V', KeyboardPacket.MODIFIER_CTRL),
            new Macro("Ctrl+Z", 'Z', KeyboardPacket.MODIFIER_CTRL),
            new Macro("Win+L", 'L', KeyboardPacket.MODIFIER_META),
    };

    private static final int BTN_PASTE = MACROS.length;       // 粘贴
    private static final int BTN_CREDENTIAL = MACROS.length + 1; // 凭据
    private static final int TOTAL_BUTTONS = MACROS.length + 2;
    private static final int ROW_SIZE = 4;

    private final NvConnection conn;
    private final UiPrefs prefs;
    private final CredentialVault vault;
    private final String hostId;

    private final Paint panelBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint btnFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint btnStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private boolean visible = false;
    private boolean pressed = false;

    private float btnH, btnW, gap, pad, panelW, panelH;
    private final RectF panelRect = new RectF();
    private final RectF[] btnRects = new RectF[TOTAL_BUTTONS];

    public MacroPanel(Context context, NvConnection conn, UiPrefs prefs,
                      CredentialVault vault, String hostId) {
        super(context);
        this.conn = conn;
        this.prefs = prefs;
        this.vault = vault;
        this.hostId = hostId != null ? hostId : "default";
        float d = context.getResources().getDisplayMetrics().density;
        btnH = 34 * d;
        btnW = 74 * d;
        gap = 6 * d;
        pad = 8 * d;
        panelW = ROW_SIZE * btnW + (ROW_SIZE - 1) * gap + pad * 2;
        panelH = 2 * btnH + gap + pad * 2;
        panelBg.setColor(Color.argb(242, 26, 29, 36));   // 对齐抽屉底 #F21A1D24
        btnFill.setColor(Color.argb(230, 35, 42, 56));   // 常态深灰卡，绘制时按下转蓝
        btnStroke.setColor(Color.argb(64, 255, 255, 255));   // 玻璃描边 25%
        btnStroke.setStrokeWidth(1f);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < btnRects.length; i++) {
            btnRects[i] = new RectF();
        }
    }

    public boolean isVisible() {
        return visible;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
        setVisibility(visible ? View.VISIBLE : View.GONE);
        // 【2026-11-21 测试轮】GONE 期间不参与布局（尺寸 0），转 VISIBLE 必须
        // requestLayout 触发真实 measure/layout，否则 onDraw 用 0 尺寸画不出面板
        if (visible) {
            requestLayout();
        }
        invalidate();
    }

    private int hitButton(float x, float y) {
        if (!visible || !panelRect.contains(x, y)) {
            return -1;
        }
        for (int i = 0; i < btnRects.length; i++) {
            if (btnRects[i].contains(x, y)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        // 顶部居中悬浮；窄屏时面板宽收缩到视口内
        float pw = Math.min(panelW, w - pad * 2);
        float colW = (pw - pad * 2 - (ROW_SIZE - 1) * gap) / ROW_SIZE;
        float left = (w - pw) / 2;
        float top = pad;
        panelRect.set(left, top, left + pw, top + panelH);
        for (int i = 0; i < btnRects.length; i++) {
            int row = i / ROW_SIZE;
            int col = i % ROW_SIZE;
            float bx = left + pad + col * (colW + gap);
            float by = top + pad + row * (btnH + gap);
            btnRects[i].set(bx, by, bx + colW, by + btnH);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!visible) return;
        canvas.drawRoundRect(panelRect, 14, 14, panelBg);
        for (int i = 0; i < btnRects.length; i++) {
            if (pressed && hitButtonLast == i) { btnFill.setColor(Color.argb(225, 59, 130, 246)); }  // 按下=强调蓝
            canvas.drawRoundRect(btnRects[i], btnRects[i].height() / 2, btnRects[i].height() / 2, btnFill);
            canvas.drawRoundRect(btnRects[i], btnRects[i].height() / 2, btnRects[i].height() / 2, btnStroke);
            String label = i < MACROS.length ? MACROS[i].label : (i == BTN_PASTE ? "粘贴" : "凭据");
            float size = btnRects[i].height() * (label.length() <= 5 ? 0.4f : 0.32f);
            textPaint.setTextSize(size);
            float ty = btnRects[i].centerY() - (textPaint.descent() + textPaint.ascent()) / 2;
            canvas.drawText(label, btnRects[i].centerX(), ty, textPaint);
        }
    }

    private int hitButtonLast = -1;

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!visible) {
            return false;
        }
        float x = event.getX();
        float y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                hitButtonLast = hitButton(x, y);
                pressed = hitButtonLast >= 0;
                invalidate();
                return pressed;

            case MotionEvent.ACTION_MOVE:
                if (pressed && hitButton(x, y) != hitButtonLast) {
                    // 滑出按钮：取消本次点击
                    hitButtonLast = -1;
                    pressed = false;
                    invalidate();
                }
                return pressed;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (pressed && hitButtonLast >= 0 && hitButton(x, y) == hitButtonLast) {
                    dispatchAction(hitButtonLast);
                }
                pressed = false;
                hitButtonLast = -1;
                invalidate();
                return true;

            default:
                return pressed;
        }
    }

    private void dispatchAction(int index) {
        if (index < MACROS.length) {
            Macro macro = MACROS[index];
            if (macro.modifier == KeyboardPacket.MODIFIER_ALT
                    || macro.modifier == KeyboardPacket.MODIFIER_META) {
                // Alt/Win 系宏走真键序列：实测 mask 修饰位（单消息带 META）在
                // Sunshine 链路上不生效（Win+R/Win+L 均无反应）；Alt+Tab 另有
                // 时序问题（同步连发被 Windows 当作同时按下立即释放）
                sendMacroSequential(macro);
                return;
            }
            conn.sendKeyboardInput(KeyboardOverlay.vkKeyMap(macro.vk),
                    KeyboardPacket.KEY_DOWN, macro.modifier, (byte) 0);
            conn.sendKeyboardInput(KeyboardOverlay.vkKeyMap(macro.vk),
                    KeyboardPacket.KEY_UP, macro.modifier, (byte) 0);
            android.util.Log.d("rd_ui", "macro " + macro.label);
        } else if (index == BTN_PASTE) {
            sendClipboard();
        } else if (index == BTN_CREDENTIAL) {
            showCredentialDialog();
        }
    }

    /**
     * Alt/Win 系宏的真键序列：修饰键 DOWN → 间隔 → 目标键敲击 → 间隔 → 修饰键 UP。
     * mask 修饰位（单消息带 META）实测在 Sunshine 链路上不生效（Win+R/Win+L 无反应）；
     * Alt+Tab 同步毫秒级连发会被 Windows 当作同时按下立即释放（任务切换器闪没）。
     * 时序：修饰 DOWN →150ms→ 键 DOWN/UP →120ms→ 修饰 UP
     */
    private void sendMacroSequential(Macro macro) {
        final short vkMod = macro.modifier == KeyboardPacket.MODIFIER_META
                ? KeyboardOverlay.vkKeyMap(0x5B)    // VK_LWIN
                : KeyboardOverlay.vkKeyMap(0x12);   // VK_MENU
        final short vkKey = KeyboardOverlay.vkKeyMap(macro.vk);
        conn.sendKeyboardInput(vkMod, KeyboardPacket.KEY_DOWN, (byte) 0, (byte) 0);
        postDelayed(() -> {
            conn.sendKeyboardInput(vkKey, KeyboardPacket.KEY_DOWN, (byte) 0, (byte) 0);
            conn.sendKeyboardInput(vkKey, KeyboardPacket.KEY_UP, (byte) 0, (byte) 0);
            android.util.Log.d("rd_ui", "macro " + macro.label + ": key sent");
            postDelayed(() -> {
                conn.sendKeyboardInput(vkMod, KeyboardPacket.KEY_UP, (byte) 0, (byte) 0);
                android.util.Log.d("rd_ui", "macro " + macro.label + ": mod released");
            }, 120);
        }, 150);
        android.util.Log.d("rd_ui", "macro " + macro.label);
    }

    /** 剪贴板注入：ClipboardManager → conn.sendUtf8Text（Android→PC 单向） */
    private void sendClipboard() {
        ClipboardManager cm = (ClipboardManager) getContext()
                .getSystemService(Context.CLIPBOARD_SERVICE);
        CharSequence text = cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null
                && cm.getPrimaryClip().getItemAt(0) != null
                ? cm.getPrimaryClip().getItemAt(0).getText() : null;
        if (text == null || text.length() == 0) {
            Toast.makeText(getContext(), "剪贴板为空", Toast.LENGTH_SHORT).show();
            return;
        }
        conn.sendUtf8Text(text.toString());
        Toast.makeText(getContext(), "已粘贴 " + text.length() + " 字符到主机", Toast.LENGTH_SHORT).show();
    }

    /** 凭据对话框：解锁密码 增/改（保存）、删（清除）、查（掩码提示）+ 自动解锁开关 + 延时可配 */
    private void showCredentialDialog() {
        if (!CredentialVault.isSupported()) {
            Toast.makeText(getContext(), "凭据保险库需 Android 6.0+", Toast.LENGTH_LONG).show();
            return;
        }
        LinearLayout container = new LinearLayout(getContext());
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        container.setPadding(pad, pad, pad, 0);

        final EditText passwordInput = new EditText(getContext());
        passwordInput.setHint("解锁密码（留空 = 不修改）");
        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        container.addView(passwordInput);

        final EditText delayInput = new EditText(getContext());
        delayInput.setHint("CAD 后延时毫秒（默认 2000）");
        delayInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        delayInput.setText(String.valueOf(prefs.getUnlockDelayMs()));
        container.addView(delayInput);

        final CheckBox autoUnlock = new CheckBox(getContext());
        autoUnlock.setText("连接后自动解锁（发 CAD+密码）");
        autoUnlock.setChecked(prefs.isAutoUnlockEnabled());
        container.addView(autoUnlock);

        new AlertDialog.Builder(getContext())
                .setTitle("凭据保险库（" + vault.getMaskedHint(hostId) + "）")
                .setView(container)
                .setPositiveButton("保存", (dialog, which) -> {
                    // 密码留空 = 不改存档；自动解锁开关与延时任一变动即写入
                    prefs.setAutoUnlockEnabled(autoUnlock.isChecked());
                    String delayText = delayInput.getText().toString().trim();
                    if (!delayText.isEmpty()) {
                        try {
                            prefs.setUnlockDelayMs(Integer.parseInt(delayText));
                        } catch (NumberFormatException e) {
                            Toast.makeText(getContext(), "延时非法，保持 " + prefs.getUnlockDelayMs() + "ms",
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                    Toast.makeText(getContext(),
                            autoUnlock.isChecked() ? "自动解锁已开启" : "自动解锁已关闭",
                            Toast.LENGTH_SHORT).show();
                    String password = passwordInput.getText().toString();
                    if (!password.isEmpty()) {
                        // Keystore 生成/加密较慢，后台线程执行（CredentialVault.runLocked 会阻塞调用线程）
                        new Thread(() -> {
                            try {
                                vault.setPassword(hostId, password);
                                ((Activity) getContext()).runOnUiThread(() ->
                                        Toast.makeText(getContext(),
                                                "已保存解锁密码（" + password.length() + " 位）",
                                                Toast.LENGTH_SHORT).show());
                            } catch (CredentialVault.VaultException e) {
                                ((Activity) getContext()).runOnUiThread(() ->
                                        Toast.makeText(getContext(), "保存失败：" + e.getMessage(),
                                                Toast.LENGTH_LONG).show());
                            }
                        }, "rd-vault-save").start();
                    }
                })
                .setNeutralButton("清除存档", (dialog, which) -> {
                    new Thread(() -> {
                        try {
                            vault.deletePassword(hostId);
                            ((Activity) getContext()).runOnUiThread(() ->
                                    Toast.makeText(getContext(),
                                            "已删除 " + hostId + " 的存档密码",
                                            Toast.LENGTH_SHORT).show());
                        } catch (CredentialVault.VaultException e) {
                            ((Activity) getContext()).runOnUiThread(() ->
                                    Toast.makeText(getContext(), "删除失败：" + e.getMessage(),
                                            Toast.LENGTH_LONG).show());
                        }
                    }, "rd-vault-clear").start();
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
