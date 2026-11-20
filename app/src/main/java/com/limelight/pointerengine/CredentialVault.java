package com.limelight.pointerengine;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.security.KeyStore;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 凭据保险库（M1 · A 线 · remote_desk fork）
 *
 * 每主机（host UUID = Game extras 的 uniqueId）一把 AndroidKeyStore AES-256/GCM 密钥，
 * 密文（IV || GCM 密文）Base64 后存 app 私有 SharedPreferences——密钥材料不出 Keystore，
 * 泄漏面仅限密文与同 app 沙箱。
 *
 * 约束：
 * - AndroidKeyStore 的 AES/GCM 需 API 23+（minSdk 21，低版本 isSupported()=false，
 *   增删改查一律拒绝并提示，不做明文降级）。
 * - Keystore 密钥生成/加解密可能触发系统弹窗或慢 IO，CRUD 走后台线程封装
 *   （{@link #runLocked}(…)）；主线程仅允许 hasPassword 的缓存读。
 */
public final class CredentialVault {

    private static final String PREFS_FILE = "remote_desk_vault";
    private static final String KEY_ENTRY_PREFIX = "rd_vault_";
    private static final String CT_PREFIX = "pw_";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final int GCM_IV_LEN = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int OP_TIMEOUT_SECONDS = 10;

    private final SharedPreferences sp;
    // hostId -> "1"/"0" 的内存缓存（主线程 hasPassword 快路径用；写操作后台线程完成后刷新）
    private final java.util.Map<String, Boolean> presenceCache = new java.util.HashMap<>();

    public CredentialVault(Context context) {
        sp = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);
    }

    /** AndroidKeyStore AES/GCM 可用性（API 23+） */
    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;
    }

    /**
     * 在后台线程执行一次保险库操作并同步等待（供 UI 线程封装调用；
     * 错误包装为 VaultException 抛回调用线程）。
     */
    public <T> T runLocked(VaultOperation<T> op) {
        final CountDownLatch latch = new CountDownLatch(1);
        final Object[] result = new Object[1];
        final RuntimeException[] error = new RuntimeException[1];
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    result[0] = op.run();
                } catch (RuntimeException e) {
                    error[0] = e;
                } finally {
                    latch.countDown();
                }
            }
        }, "rd-vault").start();
        try {
            if (!latch.await(OP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new VaultException("保险库操作超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VaultException("保险库操作被中断", e);
        }
        if (error[0] != null) {
            throw error[0];
        }
        @SuppressWarnings("unchecked")
        T out = (T) result[0];
        return out;
    }

    /** 保险库操作体（在后台线程执行） */
    public interface VaultOperation<T> {
        T run();
    }

    /** 保险库操作失败（Keystore/加解密异常包装） */
    public static class VaultException extends RuntimeException {
        public VaultException(String message) {
            super(message);
        }

        public VaultException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 保存（新增/覆盖）某主机解锁密码。覆盖时删除旧密钥重新生成
     * （Keystore 密钥不可更新，重生成 = 旧密文彻底作废）。
     *
     * @return true = 写入成功
     */
    public boolean setPassword(final String hostId, final String password) {
        Boolean ok = runLocked(new VaultOperation<Boolean>() {
            @Override
            public Boolean run() {
                if (!isSupported() || hostId == null || password == null || password.isEmpty()) {
                    return false;
                }
                try {
                    String alias = KEY_ENTRY_PREFIX + hostId;
                    KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
                    ks.load(null);
                    ks.deleteEntry(alias);   // 覆盖语义：旧密钥/旧密文作废

                    KeyGenerator kg = KeyGenerator.getInstance(
                            KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
                    kg.init(new KeyGenParameterSpec.Builder(alias,
                            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setKeySize(256)
                            .setRandomizedEncryptionRequired(true)
                            .build());
                    SecretKey key = kg.generateKey();

                    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                    cipher.init(Cipher.ENCRYPT_MODE, key);
                    byte[] ct = cipher.doFinal(password.getBytes("UTF-8"));
                    byte[] iv = cipher.getIV();
                    if (iv == null || iv.length != GCM_IV_LEN) {
                        throw new VaultException("GCM IV 长度异常: " + (iv == null ? 0 : iv.length));
                    }

                    byte[] blob = new byte[iv.length + ct.length];
                    System.arraycopy(iv, 0, blob, 0, iv.length);
                    System.arraycopy(ct, 0, blob, iv.length, ct.length);
                    sp.edit().putString(CT_PREFIX + hostId,
                            Base64.encodeToString(blob, Base64.NO_WRAP)).apply();
                    presenceCache.put(hostId, Boolean.TRUE);
                    return true;
                } catch (Exception e) {
                    throw new VaultException("保存解锁密码失败", e);
                }
            }
        });
        return ok;
    }

    /**
     * 读取某主机解锁密码；未存/不支持/解密失败返回 null。
     * 必须在后台线程调用（Keystore 解密可能慢）。
     */
    public String getPassword(final String hostId) {
        return runLocked(new VaultOperation<String>() {
            @Override
            public String run() {
                String blob = sp.getString(CT_PREFIX + hostId, null);
                if (!isSupported() || hostId == null || blob == null) {
                    return null;
                }
                try {
                    byte[] data = Base64.decode(blob, Base64.NO_WRAP);
                    if (data.length <= GCM_IV_LEN) {
                        return null;
                    }
                    byte[] iv = new byte[GCM_IV_LEN];
                    byte[] ct = new byte[data.length - GCM_IV_LEN];
                    System.arraycopy(data, 0, iv, 0, GCM_IV_LEN);
                    System.arraycopy(data, GCM_IV_LEN, ct, 0, ct.length);

                    String alias = KEY_ENTRY_PREFIX + hostId;
                    KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
                    ks.load(null);
                    SecretKey key = (SecretKey) ks.getKey(alias, null);
                    if (key == null) {
                        return null;   // 密钥丢失（换机/清数据）：密文视为无效
                    }

                    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                    cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
                    return new String(cipher.doFinal(ct), "UTF-8");
                } catch (Exception e) {
                    throw new VaultException("读取解锁密码失败（密文损坏或密钥失效）", e);
                }
            }
        });
    }

    /** 删除某主机解锁密码（密钥条目 + 密文一并清除） */
    public boolean deletePassword(final String hostId) {
        Boolean ok = runLocked(new VaultOperation<Boolean>() {
            @Override
            public Boolean run() {
                if (hostId == null) {
                    return false;
                }
                try {
                    if (isSupported()) {
                        KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
                        ks.load(null);
                        ks.deleteEntry(KEY_ENTRY_PREFIX + hostId);
                    }
                    sp.edit().remove(CT_PREFIX + hostId).apply();
                    presenceCache.put(hostId, Boolean.FALSE);
                    return true;
                } catch (Exception e) {
                    throw new VaultException("删除解锁密码失败", e);
                }
            }
        });
        return ok;
    }

    /** 是否已存密码（主线程安全：读密文存在性的内存缓存/落盘标记，不做 Keystore 操作） */
    public boolean hasPassword(String hostId) {
        if (hostId == null) {
            return false;
        }
        Boolean cached = presenceCache.get(hostId);
        if (cached != null) {
            return cached;
        }
        boolean present = sp.contains(CT_PREFIX + hostId);
        presenceCache.put(hostId, present);
        return present;
    }

    /** 已存密码位数提示（读密文长度换算，不触 Keystore；主线程安全） */
    public String getMaskedHint(String hostId) {
        String blob = sp.getString(CT_PREFIX + hostId, null);
        if (blob == null) {
            return "未保存";
        }
        try {
            byte[] data = Base64.decode(blob, Base64.NO_WRAP);
            int plainLen = data.length - GCM_IV_LEN - GCM_TAG_BITS / 8;
            return "已保存（约 " + Math.max(0, plainLen) + " 字节）";
        } catch (IllegalArgumentException e) {
            return "已保存";
        }
    }
}
