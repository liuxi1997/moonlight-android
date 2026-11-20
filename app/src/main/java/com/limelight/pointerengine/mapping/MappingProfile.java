package com.limelight.pointerengine.mapping;

import java.util.Collections;
import java.util.List;

/**
 * 按键映射方案（M1 · B 线 · remote_desk fork）
 *
 * 纯数据类（零 Android 依赖），由 {@link MappingOverlay#loadProfile} 从方案 JSON 解析填充。
 *
 * JSON schema（x/y=视口比例坐标 0..1，r=按钮半径 dp，vk=Win32 Virtual Key 码，
 * modifiers=KeyboardPacket 修饰掩码 SHIFT 0x01/CTRL 0x02/ALT 0x04/META 0x08，
 * macro=有序 VK 数组）：
 * <pre>
 * {
 *   "profile": {
 *     "name": "桌面 WASD",
 *     "keys": [
 *       {"id":"w", "label":"W", "x":0.16, "y":0.70, "r":24, "vk":87},
 *       {"id":"shift", "label":"Shift", "x":0.06, "y":0.92, "r":22, "vk":16},
 *       {"id":"win_d", "label":"Win+D", "x":0.46, "y":0.90, "r":24,
 *        "vk":0, "modifiers":8, "macro":[68]}
 *     ]
 *   }
 * }
 * </pre>
 * key 语义：macro 非空 → tap 按序逐 VK 发 DOWN→UP；否则单键 vk 的 DOWN→UP；
 * modifiers 掩码附加在序列的每一次键盘事件上。vk=0 且无 macro 的条目非法，解析时跳过。
 */
public final class MappingProfile {

    private final String name;
    private final List<KeyDef> keys;

    public MappingProfile(String name, List<KeyDef> keys) {
        this.name = name != null ? name : "";
        this.keys = Collections.unmodifiableList(keys);
    }

    /** 方案显示名（JSON profile.name），可空串 */
    public String getName() {
        return name;
    }

    /** 有效键位列表（不可变，非法条目已在解析期剔除） */
    public List<KeyDef> getKeys() {
        return keys;
    }

    /** 单个虚拟键位钮 */
    public static final class KeyDef {

        private final String id;
        private final String label;
        private final float x;          // 视口比例 0..1
        private final float y;          // 视口比例 0..1
        private final float r;          // 半径 dp
        private final short vk;         // Win32 Virtual Key 码；0=无单键（macro 方案）
        private final int modifiers;    // KeyboardPacket.MODIFIER_* 掩码 0..0x0F
        private final short[] macro;    // 有序 VK 数组；null/空=走单键 vk

        public KeyDef(String id, String label, float x, float y, float r,
                      short vk, int modifiers, short[] macro) {
            this.id = id;
            this.label = label;
            this.x = x;
            this.y = y;
            this.r = r;
            this.vk = vk;
            this.modifiers = modifiers;
            this.macro = macro;
        }

        public String getId() {
            return id;
        }

        public String getLabel() {
            return label;
        }

        /** 按钮中心 X（视口宽度比例 0..1） */
        public float getX() {
            return x;
        }

        /** 按钮中心 Y（视口高度比例 0..1） */
        public float getY() {
            return y;
        }

        /** 按钮半径（dp） */
        public float getRadiusDp() {
            return r;
        }

        /** Win32 Virtual Key 码（Limelight.h：按 US English layout 解释）；0=无 */
        public short getVk() {
            return vk;
        }

        /** 附加修饰掩码（KeyboardPacket.MODIFIER_*，0..0x0F） */
        public int getModifiers() {
            return modifiers;
        }

        /** 有序宏 VK 数组；null/空 = 单键方案 */
        public short[] getMacro() {
            return macro;
        }

        /** macro 非空 → 按序逐键 DOWN/UP；否则单键 DOWN/UP */
        public boolean hasMacro() {
            return macro != null && macro.length > 0;
        }
    }
}
