package com.limelight.pointerengine;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.limelight.binding.input.KeyboardTranslator;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.KeyboardPacket;

/**
 * 自动解锁（M1 · A 线 · remote_desk fork）
 *
 * 序列（锁屏注入，时序经 Handler 主线程编排）：
 *   1. Ctrl+Alt+Del 三键序列：VK_CONTROL → VK_MENU → VK_DELETE 逐键 DOWN/UP
 *   2. 延时（UiPrefs unlock_delay_ms，默认 2000，等安全桌面/登录框出现）
 *   3. 逐字符注入密码：字符 → Win32 VK + Shift 掩码，键间 60ms；
 *      任一字符非 US-ASCII 可映射 → 整串降级走 conn.sendUtf8Text（单通道文本）
 *   4. VK_RETURN（提交）
 *   5. 结果 Toast
 * 无密码存档：只发 CAD 并提示手动输入。
 *
 * 风险（如实）：Windows 安全桌面是否接受软件注入 SAS、锁屏对 sendUtf8Text 的接受度，
 * 本仓库无 Sunshine 源码不可离线证明，需真机验证；失败场景用户手动补输。
 *
 * 发送通道与全键盘一致：conn.sendKeyboardInput(0x80 前缀 keyMap, DOWN/UP, 修饰掩码, 0)。
 */
public final class AutoUnlocker {

    private static final long CHAR_INTERVAL_MS = 60;
    private static final long ENTER_AFTER_PASSWORD_DELAY_MS = 200;
    private static final long UTF8_FALLBACK_DELAY_MS = 300;

    private final Activity activity;
    private final UiPrefs prefs;
    private final CredentialVault vault;
    private final String hostId;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private NvConnection conn;

    public AutoUnlocker(Activity activity, NvConnection conn, CredentialVault vault,
                        UiPrefs prefs, String hostId) {
        this.activity = activity;
        this.conn = conn;
        this.vault = vault;
        this.prefs = prefs;
        this.hostId = hostId != null ? hostId : "default";
    }

    /** 切显示器重建 NvConnection 后由 Game 重绑（连接对象必须指向当前活动连接） */
    public void setConn(NvConnection conn) {
        this.conn = conn;
    }

    /** 面板「解锁」钮：手动触发 */
    public void runManual() {
        toast("发送 Ctrl+Alt+Del…");
        startSequence();
    }

    /** 设置开关：串流连接建立（connectionStarted）时自动触发；未开启则静默跳过 */
    public void onStreamConnected() {
        if (!prefs.isAutoUnlockEnabled()) {
            return;
        }
        toast("自动解锁：发送 Ctrl+Alt+Del…");
        startSequence();
    }

    private void startSequence() {
        if (conn == null) {
            toast("连接未就绪，无法发送");
            return;
        }
        sendCtrlAltDel();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                injectPasswordOrNotify();
            }
        }, Math.max(500, prefs.getUnlockDelayMs()));
    }

    /** CAD：Ctrl → Alt → Delete 逐键 DOWN/UP（修饰掩码随键携带，末键清空） */
    private void sendCtrlAltDel() {
        sendKey(KeyboardOverlay.VK_LCONTROL, KeyboardPacket.KEY_DOWN, KeyboardPacket.MODIFIER_CTRL);
        sendKey(KeyboardOverlay.VK_LMENU, KeyboardPacket.KEY_DOWN,
                (byte) (KeyboardPacket.MODIFIER_CTRL | KeyboardPacket.MODIFIER_ALT));
        sendKey(KeyboardOverlay.VK_DELETE, KeyboardPacket.KEY_DOWN,
                (byte) (KeyboardPacket.MODIFIER_CTRL | KeyboardPacket.MODIFIER_ALT));
        sendKey(KeyboardOverlay.VK_DELETE, KeyboardPacket.KEY_UP,
                (byte) (KeyboardPacket.MODIFIER_CTRL | KeyboardPacket.MODIFIER_ALT));
        sendKey(KeyboardOverlay.VK_LMENU, KeyboardPacket.KEY_UP, KeyboardPacket.MODIFIER_CTRL);
        sendKey(KeyboardOverlay.VK_LCONTROL, KeyboardPacket.KEY_UP, (byte) 0);
    }

    private void injectPasswordOrNotify() {
        if (conn == null) {
            return;
        }
        if (!CredentialVault.isSupported()) {
            toast("凭据保险库需 Android 6.0+，请手动输入密码");
            return;
        }
        // 【修复 2026-11-20 评审】本方法由主线程 Handler.postDelayed 回调进入，而
        // vault.getPassword → runLocked 以 latch.await(10s) 阻塞调用线程（Keystore 解密慢
        // 时卡满 10s），直接阻塞主线程有 ANR 风险，违反 CredentialVault 的线程契约
        // （"CRUD 走后台线程，主线程仅允许 hasPassword"）。改为后台线程取密码，
        // 取完再回主线程继续注入序列（sendKey/sendUtf8Text/Toast 时序不变）。
        new Thread(new Runnable() {
            @Override
            public void run() {
                String password = null;
                String error = null;
                try {
                    password = vault.getPassword(hostId);
                } catch (CredentialVault.VaultException e) {
                    error = "读取解锁密码失败：" + e.getMessage() + "，请手动输入";
                }
                final String fetched = password;
                final String errMsg = error;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (errMsg != null) {
                            toast(errMsg);
                            return;
                        }
                        injectPassword(fetched);
                    }
                });
            }
        }, "rd-autounlock-pw").start();
    }

    /** 主线程执行：密码就绪后的逐字符注入（或 UTF-8 降级）；password 为 null/空 = 未存档提示 */
    private void injectPassword(final String password) {
        if (conn == null) {
            return;
        }
        if (password == null || password.isEmpty()) {
            toast("未保存解锁密码，仅发送了 Ctrl+Alt+Del，请手动输入");
            return;
        }

        // 逐字符映射：全部可映射走 VK+Shift 掩码；任一失败整串降级 UTF-8 文本通道
        int[][] mapped = new int[password.length()][];
        boolean allMappable = true;
        for (int i = 0; i < password.length(); i++) {
            int[] m = mapCharToVk(password.charAt(i));
            if (m == null) {
                allMappable = false;
                break;
            }
            mapped[i] = m;
        }

        if (allMappable) {
            injectMappedChars(mapped, 0);
        } else {
            conn.sendUtf8Text(password);
            toast("密码含非 US 键盘字符，已改用 UTF-8 文本通道注入（锁屏接受度待真机验证）");
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    sendEnter();
                    toast("已提交（" + password.length() + " 字符）");
                }
            }, UTF8_FALLBACK_DELAY_MS);
        }
    }

    /** 逐字符顺序注入（VK+Shift 掩码，键间 CHAR_INTERVAL_MS） */
    private void injectMappedChars(final int[][] mapped, final int index) {
        if (conn == null) {
            return;
        }
        if (index >= mapped.length) {
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    sendEnter();
                    toast("已注入密码并回车（安全桌面注入接受度需真机验证）");
                }
            }, ENTER_AFTER_PASSWORD_DELAY_MS);
            return;
        }
        int vk = mapped[index][0];
        byte mask = mapped[index][1] != 0 ? KeyboardPacket.MODIFIER_SHIFT : 0;
        sendKey(vk, KeyboardPacket.KEY_DOWN, mask);
        sendKey(vk, KeyboardPacket.KEY_UP, mask);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                injectMappedChars(mapped, index + 1);
            }
        }, CHAR_INTERVAL_MS);
    }

    private void sendEnter() {
        if (conn == null) {
            return;
        }
        sendKey(KeyboardOverlay.VK_RETURN, KeyboardPacket.KEY_DOWN, (byte) 0);
        sendKey(KeyboardOverlay.VK_RETURN, KeyboardPacket.KEY_UP, (byte) 0);
    }

    private void sendKey(int vk, byte direction, byte modifier) {
        conn.sendKeyboardInput(KeyboardOverlay.vkKeyMap(vk), direction, modifier, (byte) 0);
    }

    private void toast(String message) {
        Toast.makeText(activity.getApplicationContext(), message, Toast.LENGTH_SHORT).show();
    }

    /**
     * US 键盘布局字符 → {Win32 VK, 需 Shift}；不可映射返回 null（触发 UTF-8 降级）。
     * 字母/数字 = KeyboardTranslator.VK_A/VK_0 偏移；符号 = Win32 OEM VK（Limelight.h:688 US layout）。
     */
    static int[] mapCharToVk(char c) {
        if (c >= 'a' && c <= 'z') {
            return new int[]{KeyboardTranslator.VK_A + (c - 'a'), 0};
        }
        if (c >= 'A' && c <= 'Z') {
            return new int[]{KeyboardTranslator.VK_A + (c - 'A'), 1};
        }
        if (c >= '0' && c <= '9') {
            return new int[]{KeyboardTranslator.VK_0 + (c - '0'), 0};
        }
        switch (c) {
            case ' ':  return new int[]{KeyboardTranslator.VK_SPACE, 0};
            case '`':  return new int[]{0xC0, 0};   // VK_OEM_3
            case '~':  return new int[]{0xC0, 1};
            case '-':  return new int[]{0xBD, 0};   // VK_OEM_MINUS
            case '_':  return new int[]{0xBD, 1};
            case '=':  return new int[]{0xBB, 0};   // VK_OEM_PLUS
            case '+':  return new int[]{0xBB, 1};
            case '[':  return new int[]{0xDB, 0};   // VK_OEM_4
            case '{':  return new int[]{0xDB, 1};
            case ']':  return new int[]{0xDD, 0};   // VK_OEM_6
            case '}':  return new int[]{0xDD, 1};
            case '\\': return new int[]{0xDC, 0};   // VK_OEM_5
            case '|':  return new int[]{0xDC, 1};
            case ';':  return new int[]{0xBA, 0};   // VK_OEM_1
            case ':':  return new int[]{0xBA, 1};
            case '\'': return new int[]{0xDE, 0};   // VK_OEM_7
            case '"':  return new int[]{0xDE, 1};
            case ',':  return new int[]{0xBC, 0};   // VK_OEM_COMMA
            case '<':  return new int[]{0xBC, 1};
            case '.':  return new int[]{0xBE, 0};   // VK_OEM_PERIOD
            case '>':  return new int[]{0xBE, 1};
            case '/':  return new int[]{0xBF, 0};   // VK_OEM_2
            case '?':  return new int[]{0xBF, 1};
            case '!':  return new int[]{KeyboardTranslator.VK_0 + 1, 1};
            case '@':  return new int[]{KeyboardTranslator.VK_0 + 2, 1};
            case '#':  return new int[]{KeyboardTranslator.VK_0 + 3, 1};
            case '$':  return new int[]{KeyboardTranslator.VK_0 + 4, 1};
            case '%':  return new int[]{KeyboardTranslator.VK_0 + 5, 1};
            case '^':  return new int[]{KeyboardTranslator.VK_0 + 6, 1};
            case '&':  return new int[]{KeyboardTranslator.VK_0 + 7, 1};
            case '*':  return new int[]{KeyboardTranslator.VK_0 + 8, 1};
            case '(':  return new int[]{KeyboardTranslator.VK_0 + 9, 1};
            case ')':  return new int[]{KeyboardTranslator.VK_0 + 0, 1};
            default:   return null;   // 中文/全角等 → UTF-8 降级
        }
    }
}
