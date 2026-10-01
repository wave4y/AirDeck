package com.airdeck.hid;

/** Visual presets only. Existing presets preserve their tested HID mapping. */
public enum GamepadPreset {
    PSP("PSP", "单摇杆 · 十字键 · 经典符号按键"),
    PS5("PS5", "对称双摇杆 · 符号按键 · 双扳机"),
    XBOX("Xbox", "错位双摇杆 · ABXY · 双扳机"),
    SWITCH("Switch", "错位双摇杆 · 任天堂按键顺序"),
    ARCADE("街机", "大摇杆 · 六键弧形排布 · 投币 / 开始"),
    GBA("GBA", "十字键 · 斜置 A / B · L / R"),
    NES("FC / NES", "十字键 · 双按键 · Select / Start"),
    SNES("SFC / SNES", "十字键 · 四色按键 · L / R"),
    N64("N64", "单摇杆 · 十字键 · A / B 与 C 四键");

    public final String label;
    public final String description;

    GamepadPreset(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public boolean hasLeftStick() { return this == PSP || hasRightStick() || this == ARCADE || this == N64; }
    public boolean hasRightStick() { return this == PS5 || this == XBOX || this == SWITCH; }
    public boolean hasDpad() { return this != ARCADE; }
    public boolean hasShoulders() { return this != ARCADE && this != NES; }
    public boolean hasLeftTrigger() { return hasRightStick() || this == N64; }
    public boolean hasRightTrigger() { return hasRightStick(); }
    public boolean hasTriggers() { return hasLeftTrigger() || hasRightTrigger(); }
    public boolean hasStickClicks() { return hasRightStick(); }
    public boolean hasSelectButton() { return this != N64; }
    public boolean usesPlayStationSymbols() { return this == PSP || this == PS5; }
    public boolean hasOffsetSticks() { return this == XBOX || this == SWITCH; }
    public boolean isClassic() { return this == GBA || this == NES || this == SNES; }

    public int faceButtonCount() {
        if (this == ARCADE || this == N64) return 6;
        return this == GBA || this == NES ? 2 : 4;
    }

    /** Existing four-key presets use south/east/west/north. New presets use drawn order. */
    public String faceLabel(int index) {
        checkFaceIndex(index);
        if (usesPlayStationSymbols()) return new String[]{"Cross", "Circle", "Square", "Triangle"}[index];
        if (this == SWITCH || this == SNES) return new String[]{"B", "A", "Y", "X"}[index];
        if (this == ARCADE) return new String[]{"LP", "MP", "HP", "LK", "MK", "HK"}[index];
        if (this == N64) return new String[]{"A", "B", "C LEFT", "C RIGHT", "C UP", "C DOWN"}[index];
        return new String[]{"A", "B", "X", "Y"}[index];
    }

    /** Every visible face key owns a distinct bit in the unchanged standard HID report. */
    public int faceHidBit(int index) {
        checkFaceIndex(index);
        if (this == N64) return new int[]{0, 1, 2, 3, 8, 9}[index];
        return index;
    }

    private void checkFaceIndex(int index) {
        if (index < 0 || index >= faceButtonCount()) throw new IllegalArgumentException("face index");
    }

    public String shoulderLabel(boolean right, boolean trigger) {
        if (this == N64 && trigger) return "Z";
        if (this == PSP || isClassic() || this == N64) return right ? "R" : "L";
        if (this == XBOX) return right ? (trigger ? "RT" : "RB") : (trigger ? "LT" : "LB");
        if (this == SWITCH) return right ? (trigger ? "ZR" : "R") : (trigger ? "ZL" : "L");
        return right ? (trigger ? "R2" : "R1") : (trigger ? "L2" : "L1");
    }

    public String menuLabel(boolean right) {
        if (this == ARCADE) return right ? "START" : "COIN";
        if (this == PSP || isClassic() || this == N64) return right ? "START" : "SELECT";
        if (this == PS5) return right ? "OPTIONS" : "CREATE";
        if (this == XBOX) return right ? "MENU" : "VIEW";
        return right ? "+" : "−";
    }

    /** Reference for an emulator's controller-binding screen; no automatic console mapping implied. */
    public String mappingDescription() {
        if (this == ARCADE) return "LP/MP/HP → A/B/X；LK/MK/HK → Y/L1/R1；COIN → Select；START → Start；摇杆 → 左摇杆。";
        if (this == N64) return "A/B → A/B；C 左/右/上/下 → X/Y/L3/R3；L/R → L1/R1；Z → L2；START → Start。";
        if (this == GBA || this == NES) return "A/B → 标准 A/B；Select/Start → 同名键；方向键 → 十字键。"+(this == GBA?" L/R → L1/R1。":"");
        if (this == SNES || this == SWITCH) return "下 B / 右 A / 左 Y / 上 X → 标准 A/B/X/Y；L/R → L1/R1。";
        if (usesPlayStationSymbols()) return "下叉 / 右圆 / 左方 / 上三角 → 标准 A/B/X/Y；L/R 或 L1/R1 → 标准肩键。";
        return "A/B/X/Y、肩键、扳机与双摇杆使用标准手柄输入。";
    }
}
