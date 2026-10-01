package com.airdeck.hid;

/** Visual presets only: HID buttons keep the same physical-position mapping. */
public enum GamepadPreset {
    PSP("PSP", "单摇杆 · 十字键 · 经典符号按键"),
    PS5("PS5", "对称双摇杆 · 符号按键 · 双扳机"),
    XBOX("Xbox", "错位双摇杆 · ABXY · 双扳机"),
    SWITCH("Switch", "错位双摇杆 · 任天堂按键顺序");

    public final String label;
    public final String description;

    GamepadPreset(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public boolean hasRightStick() { return this != PSP; }
    public boolean hasTriggers() { return this != PSP; }
    public boolean hasStickClicks() { return this != PSP; }
    public boolean usesPlayStationSymbols() { return this == PSP || this == PS5; }
    public boolean hasOffsetSticks() { return this == XBOX || this == SWITCH; }

    /** South, east, west, north. Labels never change the transmitted HID bit. */
    public String faceLabel(int position) {
        if (position < 0 || position > 3) throw new IllegalArgumentException("face position");
        if (usesPlayStationSymbols()) return new String[]{"Cross", "Circle", "Square", "Triangle"}[position];
        if (this == SWITCH) return new String[]{"B", "A", "Y", "X"}[position];
        return new String[]{"A", "B", "X", "Y"}[position];
    }

    public String shoulderLabel(boolean right, boolean trigger) {
        if (this == PSP) return right ? "R" : "L";
        if (this == XBOX) return right ? (trigger ? "RT" : "RB") : (trigger ? "LT" : "LB");
        if (this == SWITCH) return right ? (trigger ? "ZR" : "R") : (trigger ? "ZL" : "L");
        return right ? (trigger ? "R2" : "R1") : (trigger ? "L2" : "L1");
    }

    public String menuLabel(boolean right) {
        if (this == PSP) return right ? "START" : "SELECT";
        if (this == PS5) return right ? "OPTIONS" : "CREATE";
        if (this == XBOX) return right ? "MENU" : "VIEW";
        return right ? "+" : "−";
    }
}
