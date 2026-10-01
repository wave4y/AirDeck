package com.airdeck.hid;

/** Checks preset control membership and stable HID bindings without Android or a test framework. */
public final class GamepadPresetTest {
    private static int assertions;

    public static void main(String[] args) {
        originalBindingsStayCompatible();
        classicControlsDoNotExposeUnwantedAxes();
        arcadeHasSixIndependentKeys();
        n64SupportsSimultaneousCButtons();
        everyVisibleButtonHasAUniqueBinding();
        System.out.println("PASS: " + assertions + " gamepad preset assertions");
    }

    private static void originalBindingsStayCompatible() {
        for (GamepadPreset p : new GamepadPreset[]{GamepadPreset.PSP, GamepadPreset.PS5, GamepadPreset.XBOX, GamepadPreset.SWITCH}) {
            check(p.faceButtonCount() == 4, p + " retains four face controls");
            for (int index=0; index<4; index++) check(p.faceHidBit(index)==index, p+" original bit "+index);
        }
        check(!GamepadPreset.PSP.hasRightStick() && !GamepadPreset.PSP.hasTriggers() && !GamepadPreset.PSP.hasStickClicks(), "PSP does not expose hidden controls");
        check(!GamepadPreset.PS5.hasOffsetSticks() && GamepadPreset.XBOX.hasOffsetSticks() && GamepadPreset.SWITCH.hasOffsetSticks(), "established stick positions remain");
        String[] nintendo={"B","A","Y","X"};
        for(int i=0;i<4;i++) check(GamepadPreset.SWITCH.faceLabel(i).equals(nintendo[i]), "Switch physical position "+i);
    }

    private static void classicControlsDoNotExposeUnwantedAxes() {
        for(GamepadPreset p:new GamepadPreset[]{GamepadPreset.GBA,GamepadPreset.NES,GamepadPreset.SNES}) {
            check(p.hasDpad() && !p.hasLeftStick() && !p.hasRightStick(),p+" has only a digital directional control");
            check(!p.hasTriggers() && !p.hasStickClicks(),p+" has no extra triggers/stick clicks");
            check(p.hasSelectButton(),p+" retains Select");
        }
        check(GamepadPreset.GBA.faceButtonCount()==2 && GamepadPreset.NES.faceButtonCount()==2,"GBA and NES have only A/B");
        check(GamepadPreset.GBA.hasShoulders() && GamepadPreset.SNES.hasShoulders() && !GamepadPreset.NES.hasShoulders(),"classic shoulder membership");
        check(GamepadPreset.SNES.faceButtonCount()==4 && "B".equals(GamepadPreset.SNES.faceLabel(0)),"SNES uses Nintendo face order");
        rejectIndex(GamepadPreset.NES,2);rejectIndex(GamepadPreset.GBA,-1);rejectIndex(GamepadPreset.N64,6);
    }

    private static void arcadeHasSixIndependentKeys() {
        GamepadPreset p=GamepadPreset.ARCADE;
        check(p.hasLeftStick() && !p.hasDpad() && !p.hasRightStick(),"arcade uses one analog stick");
        check(p.faceButtonCount()==6 && !p.hasShoulders() && !p.hasTriggers(),"arcade exposes six action keys without duplicate shoulder controls");
        String[] labels={"LP","MP","HP","LK","MK","HK"};
        for(int i=0;i<6;i++) check(p.faceHidBit(i)==i && p.faceLabel(i).equals(labels[i]),"arcade action "+i);
        check("COIN".equals(p.menuLabel(false)) && "START".equals(p.menuLabel(true)),"arcade menu labels");
    }

    private static void n64SupportsSimultaneousCButtons() {
        GamepadPreset p=GamepadPreset.N64;
        check(p.hasLeftStick() && p.hasDpad() && !p.hasRightStick(),"N64 keeps independent stick and D-pad");
        check(p.hasShoulders() && p.hasLeftTrigger() && !p.hasRightTrigger(),"N64 has L/R and one Z trigger");
        check(!p.hasSelectButton() && !p.hasStickClicks(),"N64 omits Select and stick-click controls");
        int[] expected={0,1,2,3,8,9};
        for(int i=0;i<expected.length;i++)check(p.faceHidBit(i)==expected[i],"N64 binding "+i);
        int cMask=0;
        for(int i=2;i<6;i++)cMask|=1<<p.faceHidBit(i);
        check(Integer.bitCount(cMask)==4 && cMask==0x30c,"all four C keys have independent simultaneous bits");
        check("Z".equals(p.shoulderLabel(false,true)),"N64 Z uses the left-trigger path");
    }

    private static void everyVisibleButtonHasAUniqueBinding() {
        for(GamepadPreset p:GamepadPreset.values()) {
            int used=0;
            for(int i=0;i<p.faceButtonCount();i++) {
                int bit=p.faceHidBit(i);
                check(bit>=0 && bit<16,p+" face bit is inside the existing HID report");
                used=claim(used,bit,p+" face "+i);
            }
            if(p.hasShoulders()){used=claim(used,4,p+" L shoulder");used=claim(used,5,p+" R shoulder");}
            if(p.hasSelectButton())used=claim(used,6,p+" Select / Coin");
            used=claim(used,7,p+" Start");
            if(p.hasStickClicks()){used=claim(used,8,p+" left click");used=claim(used,9,p+" right click");}
            check(!p.label.isEmpty() && !p.description.isEmpty() && !p.mappingDescription().isEmpty(),p+" has user-facing descriptions");
        }
    }

    private static int claim(int used,int bit,String label) {
        check((used&(1<<bit))==0,label+" cannot collide with another visible key");return used|(1<<bit);
    }
    private static void rejectIndex(GamepadPreset preset,int index) {
        boolean rejected=false;
        try{preset.faceHidBit(index);}catch(IllegalArgumentException expected){rejected=true;}
        check(rejected,preset+" rejects invalid face index "+index);
    }
    private static void check(boolean condition,String message) {
        assertions++;if(!condition)throw new AssertionError(message);
    }
}
