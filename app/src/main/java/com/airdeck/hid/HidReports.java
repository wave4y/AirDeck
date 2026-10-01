package com.airdeck.hid;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/** HID report encoding, independent of Android so wire-format invariants can be tested. */
public final class HidReports {
    public static final int KEYBOARD_ID = 1;
    public static final int MOUSE_ID = 2;
    public static final int GAMEPAD_ID = 3;
    public static final int KEYBOARD_BYTES = 8;
    public static final int MOUSE_BYTES = 4;
    public static final int GAMEPAD_BYTES = 9;

    public static final int MOD_CTRL = 0x01;
    public static final int MOD_SHIFT = 0x02;
    public static final int MOD_ALT = 0x04;
    public static final int MOD_GUI = 0x08;
    public static final int KEY_ENTER = 0x28;
    public static final int KEY_ESCAPE = 0x29;
    public static final int KEY_BACKSPACE = 0x2a;
    public static final int KEY_TAB = 0x2b;
    public static final int KEY_SPACE = 0x2c;
    public static final int KEY_CAPS_LOCK = 0x39;
    public static final int KEY_F1 = 0x3a;
    public static final int KEY_INSERT = 0x49;
    public static final int KEY_HOME = 0x4a;
    public static final int KEY_PAGE_UP = 0x4b;
    public static final int KEY_DELETE = 0x4c;
    public static final int KEY_END = 0x4d;
    public static final int KEY_PAGE_DOWN = 0x4e;
    public static final int KEY_RIGHT = 0x4f;
    public static final int KEY_LEFT = 0x50;
    public static final int KEY_DOWN = 0x51;
    public static final int KEY_UP = 0x52;
    public static final int MOUSE_LEFT = 1;
    public static final int MOUSE_RIGHT = 2;
    public static final int MOUSE_MIDDLE = 4;
    public static final int HAT_NEUTRAL = 8;
    // Logical button bit positions match the on-screen controller. The descriptor translates
    // these into Linux/Android's generic A,B,C,X,Y,Z,L1,R1,L2,R2,Select,Start,Mode,L3,R3 order.
    public static final int GAME_L2_BIT = 10;
    public static final int GAME_R2_BIT = 11;

    private HidReports() { }

    /* Three top-level application collections. All payload sizes exclude the report ID.
     * ID 1: modifiers, reserved, 6 key usages; output: five keyboard LED bits.
     * ID 2: 5 mouse buttons, relative signed X/Y/wheel.
     * ID 3: 16 buttons, hat (8 = null), signed X/Y/Z/Rz sticks, unsigned Brake/Gas triggers.
     * Every collection explicitly resets relevant globals: HID globals survive END_COLLECTION.
     */
    private static final byte[] DESCRIPTOR = bytes(
        0x05,0x01, 0x09,0x06, 0xa1,0x01, 0x85,KEYBOARD_ID,
        0x05,0x07, 0x19,0xe0, 0x29,0xe7, 0x15,0x00, 0x25,0x01,
        0x75,0x01, 0x95,0x08, 0x81,0x02,
        0x75,0x08, 0x95,0x01, 0x81,0x01,
        0x05,0x08, 0x19,0x01, 0x29,0x05, 0x75,0x01, 0x95,0x05, 0x91,0x02,
        0x75,0x03, 0x95,0x01, 0x91,0x01,
        0x05,0x07, 0x19,0x00, 0x29,0x65, 0x15,0x00, 0x25,0x65,
        0x75,0x08, 0x95,0x06, 0x81,0x00, 0xc0,

        0x05,0x01, 0x09,0x02, 0xa1,0x01, 0x85,MOUSE_ID,
        0x09,0x01, 0xa1,0x00,
        0x05,0x09, 0x19,0x01, 0x29,0x05, 0x15,0x00, 0x25,0x01,
        0x75,0x01, 0x95,0x05, 0x81,0x02,
        0x75,0x03, 0x95,0x01, 0x81,0x01,
        0x05,0x01, 0x09,0x30, 0x09,0x31, 0x09,0x38,
        0x15,0x81, 0x25,0x7f, 0x75,0x08, 0x95,0x03, 0x81,0x06, 0xc0,0xc0,

        0x05,0x01, 0x09,0x05, 0xa1,0x01, 0x85,GAMEPAD_ID,
        0x05,0x09,
        0x09,0x01, 0x09,0x02, 0x09,0x04, 0x09,0x05, // A, B, X, Y
        0x09,0x07, 0x09,0x08, 0x09,0x0b, 0x09,0x0c, // L1, R1, Select, Start
        0x09,0x0e, 0x09,0x0f, 0x09,0x09, 0x09,0x0a, // L3, R3, L2, R2
        0x09,0x0d, 0x09,0x03, 0x09,0x06, 0x09,0x10, // Mode, C, Z, extra
        0x15,0x00, 0x25,0x01,
        0x75,0x01, 0x95,0x10, 0x81,0x02,
        0x05,0x01, 0x09,0x39, 0x15,0x00, 0x25,0x07,
        0x35,0x00, 0x46,0x3b,0x01, 0x65,0x14,
        0x75,0x04, 0x95,0x01, 0x81,0x42,
        0x65,0x00, 0x35,0x00, 0x45,0x00,
        0x75,0x04, 0x95,0x01, 0x81,0x01,
        0x09,0x30, 0x09,0x31, 0x09,0x32, 0x09,0x35,
        0x15,0x81, 0x25,0x7f, 0x75,0x08, 0x95,0x04, 0x81,0x02,
        0x05,0x02, 0x09,0xc5, 0x09,0xc4, 0x15,0x00, 0x26,0xff,0x00,
        0x75,0x08, 0x95,0x02, 0x81,0x02, 0xc0
    );

    public static byte[] descriptor() { return DESCRIPTOR.clone(); }

    public static byte[] keyboard(int modifiers, Collection<Integer> keys) {
        byte[] report = new byte[KEYBOARD_BYTES];
        Set<Integer> unique = new LinkedHashSet<>();
        if (keys != null) {
            for (Integer key : keys) {
                if (key == null) continue;
                if (key >= 0xe0 && key <= 0xe7) modifiers |= 1 << (key - 0xe0);
                else if (key >= 4 && key <= 0x65) unique.add(key);
            }
        }
        report[0] = (byte) modifiers;
        if (unique.size() > 6) {
            // HID ErrorRollOver in all six slots; do not silently leave a previous key held.
            Arrays.fill(report, 2, 8, (byte) 1);
        } else {
            int index = 2;
            for (int key : unique) report[index++] = (byte) key;
        }
        return report;
    }

    public static byte[] mouse(int buttons, int dx, int dy, int wheel) {
        return bytes(buttons & 31, signedAxis(dx), signedAxis(dy), signedAxis(wheel));
    }

    /** Bluetooth boot mouse reports keep ID 2 and have only buttons, X and Y. */
    public static byte[] bootMouse(int buttons, int dx, int dy) {
        return bytes(buttons & 7, signedAxis(dx), signedAxis(dy));
    }

    public static byte[] gamepad(int buttons, int hat, int lx, int ly, int rx, int ry,
                                 int leftTrigger, int rightTrigger) {
        return bytes(buttons & 255, (buttons >>> 8) & 255,
                hat >= 0 && hat <= 7 ? hat : HAT_NEUTRAL,
                signedAxis(lx), signedAxis(ly), signedAxis(rx), signedAxis(ry),
                unsignedAxis(leftTrigger), unsignedAxis(rightTrigger));
    }

    public static int signedAxis(int value) { return Math.max(-127, Math.min(127, value)); }
    public static int unsignedAxis(int value) { return Math.max(0, Math.min(255, value)); }

    public static final class KeyStroke {
        public final int usage;
        public final int modifiers;
        public KeyStroke(int usage, int modifiers) {
            this.usage = usage;
            this.modifiers = modifiers & 255;
        }
    }

    /** Physical US keyboard mapping. Null means the character has no portable HID mapping. */
    public static KeyStroke forCharacter(int codePoint) {
        if (codePoint >= 'a' && codePoint <= 'z') return new KeyStroke(4 + codePoint - 'a', 0);
        if (codePoint >= 'A' && codePoint <= 'Z') return new KeyStroke(4 + codePoint - 'A', MOD_SHIFT);
        if (codePoint >= '1' && codePoint <= '9') return new KeyStroke(0x1e + codePoint - '1', 0);
        if (codePoint == '0') return new KeyStroke(0x27, 0);
        switch (codePoint) {
            case '\n': case '\r': return new KeyStroke(KEY_ENTER, 0);
            case '\t': return new KeyStroke(KEY_TAB, 0);
            case '\b': return new KeyStroke(KEY_BACKSPACE, 0);
            case ' ': return new KeyStroke(KEY_SPACE, 0);
            default: break;
        }
        String plain = "-=[]\\;'`,./";
        String shifted = "_+{}|:\"~<>?";
        int[] usages = {0x2d,0x2e,0x2f,0x30,0x31,0x33,0x34,0x35,0x36,0x37,0x38};
        int index = codePoint <= 127 ? plain.indexOf((char) codePoint) : -1;
        if (index >= 0) return new KeyStroke(usages[index], 0);
        index = codePoint <= 127 ? shifted.indexOf((char) codePoint) : -1;
        if (index >= 0) return new KeyStroke(usages[index], MOD_SHIFT);
        index = codePoint <= 127 ? "!@#$%^&*()".indexOf((char) codePoint) : -1;
        if (index >= 0) return new KeyStroke(index == 9 ? 0x27 : 0x1e + index, MOD_SHIFT);
        return null;
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) result[i] = (byte) values[i];
        return result;
    }
}
