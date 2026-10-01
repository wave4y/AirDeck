package com.airdeck.hid;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Platform-independent standard and simple keyboards in physical key units. */
public final class KeyboardLayout {
    public static final float WIDTH=18.5f, HEIGHT=6.5f;
    private static final List<Key> KEYS=create();
    private static final List<Key> COMPACT_KEYS=createCompact();
    private static final List<Key> EDIT_KEYS=createEdit();
    private static final List<Key> ARROW_KEYS=createArrows();
    private static final List<Key> PORTRAIT_FULL_KEYS=createPortrait(KEYS,true);
    private static final List<Key> PORTRAIT_MAIN_KEYS=createPortrait(COMPACT_KEYS,false);
    private static final List<Key> EDIT_ROW_KEYS=createEditRow();
    private static final List<Key> ARROW_ROW_KEYS=createArrowRow();
    private static final List<Key> SIMPLE_KEYS=createSimple(true);
    private static final List<Key> SIMPLE_MAIN_KEYS=createSimple(false);
    public enum Area {
        FULL(18.5f,6.5f),COMPACT_MAIN(15,6.5f),EDIT(3,2),ARROWS(3,2),
        PORTRAIT_FULL(15,9,true),PORTRAIT_MAIN(15,7,true),EDIT_ROW(6,1,true),ARROW_ROW(4,1,true),
        SIMPLE_FULL(10,8,true),SIMPLE_MAIN(10,7,true);
        public final float width,height;
        public final boolean stretchVertically;
        Area(float width,float height){this(width,height,false);}
        Area(float width,float height,boolean stretch){this.width=width;this.height=height;this.stretchVertically=stretch;}
    }
    private KeyboardLayout() { }
    public static final class Key {
        public final int usage;
        public final String normal,shifted,description;
        public final float x,y,width,height;
        Key(int usage,String normal,String shifted,String description,float x,float y,float width){
            this.usage=usage;this.normal=normal;this.shifted=shifted;this.description=description;
            this.x=x;this.y=y;this.width=width;this.height=1;
        }
        public boolean isModifier(){return usage>=0xe0&&usage<=0xe7;}
        public boolean isLetter(){return usage>=4&&usage<=29;}
        public String label(boolean shift,boolean caps){
            if(isLetter())return shift^caps?normal.toUpperCase(Locale.ROOT):normal;
            return shift&&shifted!=null?shifted:normal;
        }
    }
    public static List<Key> keys(){return KEYS;}
    public static List<Key> keys(Area area){
        switch(area){case COMPACT_MAIN:return COMPACT_KEYS;case EDIT:return EDIT_KEYS;case ARROWS:return ARROW_KEYS;
            case PORTRAIT_FULL:return PORTRAIT_FULL_KEYS;case PORTRAIT_MAIN:return PORTRAIT_MAIN_KEYS;
            case EDIT_ROW:return EDIT_ROW_KEYS;case ARROW_ROW:return ARROW_ROW_KEYS;
            case SIMPLE_FULL:return SIMPLE_KEYS;case SIMPLE_MAIN:return SIMPLE_MAIN_KEYS;default:return KEYS;}
    }
    public static Key find(int usage){for(Key key:KEYS)if(key.usage==usage)return key;return null;}
    public static final class Bounds {
        public final int left,top,right,bottom;
        Bounds(int left,int top,int right,int bottom){this.left=left;this.top=top;this.right=right;this.bottom=bottom;}
        public int width(){return right-left;}
        public int height(){return bottom-top;}
    }
    public static Bounds bounds(Key key,int width,int height,float density){
        return bounds(Area.FULL,key,width,height,density);
    }
    public static Bounds bounds(Area area,Key key,int width,int height,float density){
        float contentHeight=area.stretchVertically?height:Math.min(height,width/area.width*2f*area.height),offsetY=(height-contentHeight)/2f;
        float ux=width/area.width,uy=contentHeight/area.height;
        float gx=Math.min(Math.round(3*density),ux*.12f),gy=Math.min(Math.round(4*density),uy*.12f);
        int x=Math.max(0,Math.min(width,Math.round(key.x*ux+gx/2))),y=Math.max(0,Math.min(height,Math.round(offsetY+key.y*uy+gy/2)));
        int right=Math.max(x,Math.min(width,Math.round((key.x+key.width)*ux-gx/2))),bottom=Math.max(y,Math.min(height,Math.round(offsetY+(key.y+1)*uy-gy/2)));
        return new Bounds(x,y,right,bottom);
    }
    public static final class State {
        private final Set<Integer> pressed=new LinkedHashSet<>();
        private int leds;
        public boolean setPressed(int usage,boolean down){if(find(usage)==null)return false;return down?pressed.add(usage):pressed.remove(usage);}
        public boolean isPressed(int usage){return pressed.contains(usage);}
        public Set<Integer> pressedUsages(){return new LinkedHashSet<>(pressed);}
        public int modifierMask(){int mask=0;for(int usage:pressed)if(usage>=0xe0&&usage<=0xe7)mask|=1<<(usage-0xe0);return mask;}
        public boolean shift(){return (modifierMask()&0x22)!=0;}
        public boolean caps(){return (leds&2)!=0;}
        public boolean scrollLock(){return (leds&4)!=0;}
        public void setKeyboardLeds(int value){leds=value&31;}
        public int keyboardLeds(){return leds;}
        public void releaseAll(){pressed.clear();}
        public String label(Key key){return key.label(shift(),caps());}
    }
    private static List<Key> create(){
        List<Key> k=new ArrayList<>();
        add(k,41,"Esc",null,"Escape",0,0,1);
        for(int i=0;i<12;i++)add(k,58+i,"F"+(i+1),null,"F"+(i+1),2+i+(i/4)*.5f,0,1);
        add(k,70,"PrtSc",null,"Print Screen 截屏",15.5f,0,1);add(k,71,"ScrLk",null,"Scroll Lock 滚动锁定",16.5f,0,1);add(k,72,"Pause",null,"Pause 暂停",17.5f,0,1);
        add(k,53,"`","~","反引号",0,1.5f,1);
        String[] numberShift={"!","@","#","$","%","^","&","*","(",")"};
        for(int i=0;i<10;i++)add(k,30+i,i==9?"0":Integer.toString(i+1),numberShift[i],"数字 "+(i==9?0:i+1),i+1,1.5f,1);
        add(k,45,"-","_","减号",11,1.5f,1);add(k,46,"=","+","等号",12,1.5f,1);add(k,42,"⌫",null,"Backspace 退格",13,1.5f,2);
        add(k,73,"Ins",null,"Insert 插入",15.5f,1.5f,1);add(k,74,"Home",null,"Home 行首",16.5f,1.5f,1);add(k,75,"PgUp",null,"Page Up 上翻页",17.5f,1.5f,1);
        add(k,43,"Tab",null,"Tab 制表",0,2.5f,1.5f);letters(k,"qwertyuiop",1.5f,2.5f);
        add(k,47,"[","{","左方括号",11.5f,2.5f,1);add(k,48,"]","}","右方括号",12.5f,2.5f,1);add(k,49,"\\","|","反斜杠",13.5f,2.5f,1.5f);
        add(k,76,"Del",null,"Delete 删除",15.5f,2.5f,1);add(k,77,"End",null,"End 行尾",16.5f,2.5f,1);add(k,78,"PgDn",null,"Page Down 下翻页",17.5f,2.5f,1);
        add(k,57,"Caps",null,"Caps Lock 大写锁定",0,3.5f,1.75f);letters(k,"asdfghjkl",1.75f,3.5f);
        add(k,51,";",":","分号",10.75f,3.5f,1);add(k,52,"'","\"","单引号",11.75f,3.5f,1);add(k,40,"Enter",null,"Enter 回车",12.75f,3.5f,2.25f);
        add(k,0xe1,"Shift",null,"左 Shift，按住切换大写与符号",0,4.5f,2.25f);letters(k,"zxcvbnm",2.25f,4.5f);
        add(k,54,",","<","逗号",9.25f,4.5f,1);add(k,55,".",">","句号",10.25f,4.5f,1);add(k,56,"/","?","斜杠",11.25f,4.5f,1);add(k,0xe5,"Shift",null,"右 Shift，按住切换大写与符号",12.25f,4.5f,2.75f);
        add(k,82,"↑",null,"上方向键",16.5f,4.5f,1);
        add(k,0xe0,"Ctrl",null,"左 Ctrl",0,5.5f,1.25f);add(k,0xe3,"Meta",null,"左 Meta / Windows",1.25f,5.5f,1.25f);add(k,0xe2,"Alt",null,"左 Alt",2.5f,5.5f,1.25f);
        add(k,44,"空格",null,"Space 空格",3.75f,5.5f,6.25f);add(k,0xe6,"Alt",null,"右 Alt",10,5.5f,1.25f);add(k,0xe7,"Meta",null,"右 Meta / Windows",11.25f,5.5f,1.25f);
        add(k,0x65,"Menu",null,"Application Menu 菜单",12.5f,5.5f,1.25f);add(k,0xe4,"Ctrl",null,"右 Ctrl",13.75f,5.5f,1.25f);
        add(k,80,"←",null,"左方向键",15.5f,5.5f,1);add(k,81,"↓",null,"下方向键",16.5f,5.5f,1);add(k,79,"→",null,"右方向键",17.5f,5.5f,1);
        return Collections.unmodifiableList(k);
    }
    private static List<Key> createCompact(){
        List<Key> compact=new ArrayList<>();
        for(Key key:KEYS){
            if(key.x>=15||key.usage>=0xe4&&key.usage<=0xe7)continue;
            float x=key.x,w=key.width;
            if(key.y==4.5f&&key.usage!=0xe1){x=2.25f+(key.x-2.25f)*1.275f;w=1.275f;}
            if(key.y==5.5f){
                if(key.usage==0xe0){x=0;w=1.5f;}else if(key.usage==0xe3){x=1.5f;w=1.5f;}
                else if(key.usage==0xe2){x=3;w=1.5f;}else if(key.usage==44){x=4.5f;w=9;}
                else if(key.usage==101){x=13.5f;w=1.5f;}
            }
            compact.add(copy(key,x,key.y,w));
        }
        return Collections.unmodifiableList(compact);
    }
    private static List<Key> createEdit(){
        List<Key> edit=new ArrayList<>();int[] usages={73,74,75,76,77,78};
        for(int i=0;i<usages.length;i++)edit.add(copy(find(usages[i]),i%3,i/3,1));
        return Collections.unmodifiableList(edit);
    }
    private static List<Key> createArrows(){
        List<Key> arrows=new ArrayList<>();arrows.add(copy(find(82),1,0,1));arrows.add(copy(find(80),0,1,1));
        arrows.add(copy(find(81),1,1,1));arrows.add(copy(find(79),2,1,1));return Collections.unmodifiableList(arrows);
    }
    private static List<Key> createPortrait(List<Key> source,boolean full){
        List<Key> portrait=new ArrayList<>();
        evenRow(portrait,new int[]{41,58,59,60,61,62,63},0);
        evenRow(portrait,full?new int[]{64,65,66,67,68,69,70,71,72}:new int[]{64,65,66,67,68,69},1);
        for(Key key:source)if(key.y>0&&key.x<15)portrait.add(copy(key,key.x,key.y+.5f,key.width));
        if(full){evenRow(portrait,new int[]{73,74,75,76,77,78},7);evenRow(portrait,new int[]{80,82,81,79},8);}
        return Collections.unmodifiableList(portrait);
    }
    private static void evenRow(List<Key> keys,int[] usages,float y){float unit=15f/usages.length;for(int i=0;i<usages.length;i++)keys.add(copy(find(usages[i]),i*unit,y,unit));}
    private static List<Key> createEditRow(){List<Key> keys=new ArrayList<>();int[] usages={73,74,75,76,77,78};for(int i=0;i<usages.length;i++)keys.add(copy(find(usages[i]),i,0,1));return Collections.unmodifiableList(keys);}
    private static List<Key> createArrowRow(){List<Key> keys=new ArrayList<>();int[] usages={80,82,81,79};for(int i=0;i<usages.length;i++)keys.add(copy(find(usages[i]),i,0,1));return Collections.unmodifiableList(keys);}
    private static List<Key> createSimple(boolean withArrows){
        List<Key> simple=new ArrayList<>();
        // Keep the legacy simple keyboard's Esc/F1-F12/Tab available without scrolling.
        int[] functions={41,58,59,60,61,62,63,64,65,66,67,68,69,43};
        for(int i=0;i<functions.length;i++){
            int column=i%7;float x=column*10f/7f,right=(column+1)*10f/7f;
            simple.add(copy(find(functions[i]),x,i/7,right-x));
        }
        for(int i=0;i<10;i++)simple.add(copy(find(30+i),i,2,1));
        simpleLetters(simple,"qwertyuiop",0,3);
        simpleLetters(simple,"asdfghjkl",.5f,4);
        simple.add(copy(find(0xe1),0,5,1.5f));
        simpleLetters(simple,"zxcvbnm",1.5f,5);
        simple.add(copy(find(42),8.5f,5,1.5f));
        int[] bottom={0xe0,0xe3,0xe2,54,44,55,56,40};
        float[] positions={0,1,2,3,4,6,7,8},widths={1,1,1,1,2,1,1,2};
        for(int i=0;i<bottom.length;i++)simple.add(copy(find(bottom[i]),positions[i],6,widths[i]));
        if(withArrows){int[] arrows={80,82,81,79};for(int i=0;i<arrows.length;i++)simple.add(copy(find(arrows[i]),i*2.5f,7,2.5f));}
        return Collections.unmodifiableList(simple);
    }
    private static void simpleLetters(List<Key> keys,String letters,float x,float y){
        for(int i=0;i<letters.length();i++)keys.add(copy(find(4+letters.charAt(i)-'a'),x+i,y,1));
    }
    private static Key copy(Key key,float x,float y,float width){return new Key(key.usage,key.normal,key.shifted,key.description,x,y,width);}
    private static void letters(List<Key> keys,String letters,float x,float y){for(int i=0;i<letters.length();i++){char letter=letters.charAt(i);add(keys,4+letter-'a',Character.toString(letter),null,"字母 "+letter,x+i,y,1);}}
    private static void add(List<Key> keys,int usage,String normal,String shifted,String description,float x,float y,float width){keys.add(new Key(usage,normal,shifted,description,x,y,width));}
}
