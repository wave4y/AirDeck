package com.airdeck.hid;

/** Keyboard presentations share HID mappings; only the visible keys and layout change. */
public enum KeyboardPreset {
    STANDARD("标准", "完整键位与功能键"),
    SIMPLE("简洁", "数字、字母与常用键，更大的按键");

    public final String label;
    public final String description;

    KeyboardPreset(String label, String description) {
        this.label = label;
        this.description = description;
    }
}
