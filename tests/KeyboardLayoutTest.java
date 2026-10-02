package com.airdeck.hid;
import java.util.HashSet;
import java.util.Set;
public final class KeyboardLayoutTest {
    private static int assertions;
    private static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
    private static KeyboardLayout.Key portraitKey(int usage){
        for(KeyboardLayout.Key key:KeyboardLayout.keys(KeyboardLayout.Area.PORTRAIT_FULL))if(key.usage==usage)return key;
        throw new AssertionError("missing portrait usage "+usage);
    }
    private static void checkPortraitTypingRows(){
        KeyboardLayout.Area area=KeyboardLayout.Area.PORTRAIT_FULL;
        KeyboardLayout.Key letter=portraitKey(4);
        // The portrait layout must make the typing keys wider, rather than merely shrinking an ANSI board.
        for(int usage=4;usage<=39;usage++){
            KeyboardLayout.Key key=portraitKey(usage);
            check(key.width/area.width>=.1f-.00001f,"portrait typing key occupies at least a tenth of the width "+usage);
            check(Math.abs(key.height-letter.height)<.0001f,"consistent letter and digit heights "+usage);
        }
        String[] typingRows={"qwertyuiop","asdfghjkl","zxcvbnm"};
        float previousY=-1;
        for(String row:typingRows){
            KeyboardLayout.Key first=portraitKey(4+row.charAt(0)-'a');
            check(first.y>previousY,"QWERTY rows remain in typing order");previousY=first.y;
            float previousRight=-1;
            for(int i=0;i<row.length();i++){
                KeyboardLayout.Key key=portraitKey(4+row.charAt(i)-'a');
                check(Math.abs(key.y-first.y)<.0001f&&key.x>=previousRight-.0001f,"QWERTY letters remain ordered on their row");
                previousRight=key.x+key.width;
            }
        }
        int[] auxiliary={41,58,59,60,61,62,63,64,65,66,67,68,69,70,71,72,73,74,75,76,77,78};
        KeyboardLayout.Bounds letterBounds=KeyboardLayout.bounds(area,letter,360,600,1);
        for(int usage:auxiliary){
            KeyboardLayout.Key key=portraitKey(usage);
            check(key.height>0&&key.height<letter.height,"auxiliary rows give height back to typing keys "+usage);
            KeyboardLayout.Bounds bounds=KeyboardLayout.bounds(area,key,360,600,1);
            check(bounds.height()<letterBounds.height(),"pixel bounds honor shorter auxiliary keys "+usage);
        }
    }
    public static void main(String[] args){
        check(KeyboardLayout.keys().size()==87,"exactly 87 keys");Set<Integer> usages=new HashSet<>();
        for(KeyboardLayout.Key key:KeyboardLayout.keys()){
            check(usages.add(key.usage),"unique usage "+key.usage);
            check(key.x>=0&&key.y>=0&&key.x+key.width<=KeyboardLayout.WIDTH&&key.y+key.height<=KeyboardLayout.HEIGHT,"layout bounds "+key.normal);
            check(key.description!=null&&!key.description.isEmpty(),"accessible name");check(key.isModifier()||key.usage<=0x65,"supported usage");
        }
        Set<Integer> expected=new HashSet<>();for(int u=4;u<=82;u++)if(u!=50)expected.add(u);expected.add(101);for(int u=224;u<=231;u++)expected.add(u);
        check(usages.equals(expected),"complete ANSI TKL key set");
        int[] rows={16,17,17,13,13,11};float[] ys={0,1.5f,2.5f,3.5f,4.5f,5.5f};
        for(int i=0;i<ys.length;i++){int n=0;for(KeyboardLayout.Key key:KeyboardLayout.keys())if(key.y==ys[i])n++;check(n==rows[i],"row "+i);}
        check(KeyboardLayout.find(44).width==6.25f,"ANSI space width");check(KeyboardLayout.find(0xe1).width==2.25f&&KeyboardLayout.find(0xe5).width==2.75f,"ANSI Shift widths");
        check(KeyboardLayout.find(82).x==16.5f&&KeyboardLayout.find(82).y==4.5f,"independent arrows");
        int[][] sizes={{340,420},{500,354},{770,354},{320,180},{600,900},{1200,800},{1,1},{0,0}};
        float[] densities={1,1.5f,2,2.75f,3.5f};
        for(int[] size:sizes)for(float density:densities){
            int width=Math.round(size[0]*density),height=Math.round(size[1]*density);
            for(int i=0;i<87;i++){
                KeyboardLayout.Bounds a=KeyboardLayout.bounds(KeyboardLayout.keys().get(i),width,height,density);
                check(a.left>=0&&a.top>=0&&a.right<=width&&a.bottom<=height&&a.width()>=0&&a.height()>=0,"pixel bounds "+width+"x"+height);
                if(width>=320)check(a.width()>0&&a.height()>0,"all keys visible");
                for(int j=i+1;j<87;j++){
                    KeyboardLayout.Bounds b=KeyboardLayout.bounds(KeyboardLayout.keys().get(j),width,height,density);
                    if(a.width()>0&&a.height()>0&&b.width()>0&&b.height()>0)check(!(a.left<b.right&&b.left<a.right&&a.top<b.bottom&&b.top<a.bottom),"pixel overlap "+i+"/"+j);
                }
            }
        }
        KeyboardLayout.State state=new KeyboardLayout.State();
        for(int u=4;u<=29;u++)check(state.label(KeyboardLayout.find(u)).equals(Character.toString((char)('a'+u-4))),"lowercase "+u);
        state.setPressed(0xe1,true);check(state.modifierMask()==2,"left Shift bit");
        for(int u=4;u<=29;u++)check(state.label(KeyboardLayout.find(u)).equals(Character.toString((char)('A'+u-4))),"uppercase "+u);
        int[] symbols={53,30,31,32,33,34,35,36,37,38,39,45,46,47,48,49,51,52,54,55,56};
        String[] shifted={"~","!","@","#","$","%","^","&","*","(",")","_","+","{","}","|",":","\"","<",">","?"};
        for(int i=0;i<symbols.length;i++)check(state.label(KeyboardLayout.find(symbols[i])).equals(shifted[i]),"Shift symbol "+symbols[i]);
        state.setPressed(0xe5,true);check(state.modifierMask()==0x22,"both Shifts");state.setPressed(0xe1,false);check(state.shift()&&state.modifierMask()==0x20,"right Shift remains");
        state.setPressed(0xe5,false);check(!state.shift()&&state.label(KeyboardLayout.find(4)).equals("a"),"release lowercase");
        state.setKeyboardLeds(2);check(state.caps()&&state.label(KeyboardLayout.find(4)).equals("A"),"host Caps uppercase");check(state.label(KeyboardLayout.find(30)).equals("1"),"Caps leaves digits");
        state.setPressed(0xe1,true);check(state.label(KeyboardLayout.find(4)).equals("a"),"Caps XOR Shift");check(state.label(KeyboardLayout.find(30)).equals("!"),"Caps plus shifted digit");
        state.releaseAll();check(!state.shift()&&state.pressedUsages().isEmpty(),"release all");check(state.caps(),"release preserves host LEDs");
        state.setKeyboardLeds(4);check(state.scrollLock()&&!state.caps(),"LED update");
        for(int u=224;u<=231;u++)state.setPressed(u,true);check(state.modifierMask()==255,"eight modifier bits");
        for(int u=224;u<=231;u++){state.setPressed(u,false);check((state.modifierMask()&(1<<(u-224)))==0,"modifier release "+u);}
        check(!state.setPressed(0x66,true),"reject unsupported usage");
        check(KeyboardLayout.keys(KeyboardLayout.Area.COMPACT_MAIN).size()==70,"70 compact main keys");
        check(KeyboardLayout.keys(KeyboardLayout.Area.EDIT).size()==6,"six editing keys");
        check(KeyboardLayout.keys(KeyboardLayout.Area.ARROWS).size()==4,"four independent arrows");
        Set<Integer> combo=new HashSet<>();
        KeyboardLayout.Area[] comboAreas={KeyboardLayout.Area.COMPACT_MAIN,KeyboardLayout.Area.EDIT,KeyboardLayout.Area.ARROWS};
        for(KeyboardLayout.Area area:comboAreas)for(KeyboardLayout.Key key:KeyboardLayout.keys(area)){
            check(combo.add(key.usage),"unique cross-region combo usage "+key.usage);
            check(key.x>=0&&key.y>=0&&key.x+key.width<=area.width+.0001f&&key.y+key.height<=area.height,"region bounds "+key.normal);
            check(KeyboardLayout.find(key.usage).normal.equals(key.normal),"base legend preserved");
            check(KeyboardLayout.find(key.usage).shifted==null?key.shifted==null:KeyboardLayout.find(key.usage).shifted.equals(key.shifted),"corner Shift legend preserved");
        }
        Set<Integer> expectedCombo=new HashSet<>(expected);int[] omitted={0xe4,0xe5,0xe6,0xe7,70,71,72};
        for(int usage:omitted)expectedCombo.remove(usage);check(combo.equals(expectedCombo)&&combo.size()==80,"requested 80-key compact union");
        for(KeyboardLayout.Area area:comboAreas)for(int[] size:sizes)for(float density:densities){
            int width=Math.round(size[0]*density),height=Math.round(size[1]*density);
            java.util.List<KeyboardLayout.Key> region=KeyboardLayout.keys(area);
            for(int i=0;i<region.size();i++){
                KeyboardLayout.Bounds a=KeyboardLayout.bounds(area,region.get(i),width,height,density);
                check(a.left>=0&&a.top>=0&&a.right<=width&&a.bottom<=height&&a.width()>=0&&a.height()>=0,"region pixel bounds");
                if(width>=320)check(a.width()>0&&a.height()>0,"region keys visible");
                for(int j=i+1;j<region.size();j++){
                    KeyboardLayout.Bounds b=KeyboardLayout.bounds(area,region.get(j),width,height,density);
                    if(a.width()>0&&a.height()>0&&b.width()>0&&b.height()>0)check(!(a.left<b.right&&b.left<a.right&&a.top<b.bottom&&b.top<a.bottom),"region overlap "+area+" "+i+"/"+j);
                }
            }
        }
        state.releaseAll();state.setKeyboardLeds(0);state.setPressed(0xe1,true);state.setPressed(73,true);
        check(state.shift()&&state.isPressed(73),"cross-region Shift and Insert held");state.setPressed(73,false);
        check(state.shift(),"releasing editing key preserves main-region Shift");state.releaseAll();
        check(KeyboardLayout.keys(KeyboardLayout.Area.PORTRAIT_FULL).size()==87,"87 portrait keys");
        check(KeyboardLayout.keys(KeyboardLayout.Area.PORTRAIT_MAIN).size()==70,"70 portrait main keys");
        Set<Integer> portraitFull=new HashSet<>(),portraitCombo=new HashSet<>();
        for(KeyboardLayout.Key key:KeyboardLayout.keys(KeyboardLayout.Area.PORTRAIT_FULL)){
            check(portraitFull.add(key.usage),"portrait full unique");
            KeyboardLayout.Key base=KeyboardLayout.find(key.usage);
            check(base.normal.equals(key.normal)&&base.description.equals(key.description),"portrait preserves key label and accessible meaning");
            check(base.shifted==null?key.shifted==null:base.shifted.equals(key.shifted),"portrait preserves Shift corner legend");
        }
        check(portraitFull.equals(expected),"portrait full preserves all 87 functions");
        checkPortraitTypingRows();
        KeyboardLayout.Area[] portraitAreas={KeyboardLayout.Area.PORTRAIT_MAIN,KeyboardLayout.Area.EDIT_ROW,KeyboardLayout.Area.ARROW_ROW};
        for(KeyboardLayout.Area area:portraitAreas)for(KeyboardLayout.Key key:KeyboardLayout.keys(area))check(portraitCombo.add(key.usage),"portrait combo unique");
        check(portraitCombo.equals(expectedCombo),"portrait compact preserves 80 functions");
        KeyboardLayout.Area[] allPortrait={KeyboardLayout.Area.PORTRAIT_FULL,KeyboardLayout.Area.PORTRAIT_MAIN,KeyboardLayout.Area.EDIT_ROW,KeyboardLayout.Area.ARROW_ROW};
        for(KeyboardLayout.Area area:allPortrait){
            java.util.List<KeyboardLayout.Key> region=KeyboardLayout.keys(area);
            for(int i=0;i<region.size();i++){
                KeyboardLayout.Key a=region.get(i);
                check(a.x>=0&&a.y>=0&&a.width>0&&a.height>0&&a.x+a.width<=area.width+.0001f&&a.y+a.height<=area.height+.0001f,"portrait key-unit bounds including variable heights "+area+" "+a.usage);
                for(int j=i+1;j<region.size();j++){
                    KeyboardLayout.Key b=region.get(j);
                    check(!(a.x<b.x+b.width-.0001f&&b.x<a.x+a.width-.0001f&&a.y<b.y+b.height-.0001f&&b.y<a.y+a.height-.0001f),"portrait key-unit overlap "+area+" "+a.usage+"/"+b.usage);
                }
            }
        }
        for(KeyboardLayout.Area area:allPortrait)for(int[] size:sizes)for(float density:densities){
            int width=Math.round(size[0]*density),height=Math.round(size[1]*density);java.util.List<KeyboardLayout.Key> region=KeyboardLayout.keys(area);
            for(int i=0;i<region.size();i++){
                KeyboardLayout.Bounds a=KeyboardLayout.bounds(area,region.get(i),width,height,density);
                check(a.left>=0&&a.top>=0&&a.right<=width&&a.bottom<=height&&a.width()>=0&&a.height()>=0,"portrait bounds");
                if(width>=320)check(a.width()>0&&a.height()>0,"portrait visible");
                for(int j=i+1;j<region.size();j++){KeyboardLayout.Bounds b=KeyboardLayout.bounds(area,region.get(j),width,height,density);
                    if(a.width()>0&&a.height()>0&&b.width()>0&&b.height()>0)check(!(a.left<b.right&&b.left<a.right&&a.top<b.bottom&&b.top<a.bottom),"portrait overlap "+area+" "+i+"/"+j);}
            }
            if(width>=320){int top=height,bottom=0;for(KeyboardLayout.Key key:region){KeyboardLayout.Bounds b=KeyboardLayout.bounds(area,key,width,height,density);top=Math.min(top,b.top);bottom=Math.max(bottom,b.bottom);}
                check(top<=Math.round(3*density)&&bottom>=height-Math.round(3*density),"portrait uses available height");}
        }
        check(KeyboardPreset.values().length==2,"two keyboard presentations");
        check(KeyboardPreset.STANDARD.label.equals("标准")&&KeyboardPreset.SIMPLE.label.equals("简洁"),"preset labels");
        check(KeyboardLayout.keys(KeyboardLayout.Area.SIMPLE_FULL).size()==64,"64 simple keyboard keys");
        check(KeyboardLayout.keys(KeyboardLayout.Area.SIMPLE_MAIN).size()==60,"60 simple main keys");
        Set<Integer> expectedSimple=new HashSet<>();
        for(int u=4;u<=39;u++)expectedSimple.add(u);
        int[] simpleControls={0xe0,0xe1,0xe2,0xe3,40,42,44,54,55,56,80,81,82,79};
        for(int usage:simpleControls)expectedSimple.add(usage);
        Set<Integer> previousSimple=new HashSet<>(expectedSimple);
        expectedSimple.add(41);expectedSimple.add(43);for(int usage=58;usage<=69;usage++)expectedSimple.add(usage);
        Set<Integer> simpleFull=new HashSet<>(),simpleCombo=new HashSet<>();
        for(KeyboardLayout.Key key:KeyboardLayout.keys(KeyboardLayout.Area.SIMPLE_FULL)){
            check(simpleFull.add(key.usage),"simple full unique usage");
            check(KeyboardLayout.find(key.usage).normal.equals(key.normal),"simple uses standard HID label");
        }
        check(simpleFull.equals(expectedSimple),"simple has full common keys plus legacy Esc Tab F1-F12");
        check(previousSimple.size()==50&&simpleFull.containsAll(previousSimple),"all previous 50 simple functions preserved");
        check(simpleFull.contains(41)&&simpleFull.contains(43),"legacy Esc and Tab preserved");
        for(int usage=58;usage<=69;usage++)check(simpleFull.contains(usage),"legacy F key preserved "+usage);
        for(KeyboardLayout.Key key:KeyboardLayout.keys(KeyboardLayout.Area.SIMPLE_MAIN))check(simpleCombo.add(key.usage),"simple main unique usage");
        for(KeyboardLayout.Key key:KeyboardLayout.keys(KeyboardLayout.Area.ARROWS))check(simpleCombo.add(key.usage),"simple mouse arrows not duplicated");
        check(simpleCombo.equals(expectedSimple),"simple standalone and combination expose identical functions");
        int[] simpleRows={7,7,10,10,9,9,8,4};
        for(int row=0;row<simpleRows.length;row++){int count=0;for(KeyboardLayout.Key key:KeyboardLayout.keys(KeyboardLayout.Area.SIMPLE_FULL))if(key.y==row)count++;check(count==simpleRows[row],"simple row count "+row);}
        KeyboardLayout.Area[] simpleAreas={KeyboardLayout.Area.SIMPLE_FULL,KeyboardLayout.Area.SIMPLE_MAIN};
        for(KeyboardLayout.Area area:simpleAreas)for(int[] size:sizes)for(float density:densities){
            int width=Math.round(size[0]*density),height=Math.round(size[1]*density);
            java.util.List<KeyboardLayout.Key> region=KeyboardLayout.keys(area);
            for(int i=0;i<region.size();i++){
                KeyboardLayout.Key key=region.get(i);
                check(key.x>=0&&key.y>=0&&key.x+key.width<=area.width&&key.y+1<=area.height,"simple key-unit bounds");
                KeyboardLayout.Bounds a=KeyboardLayout.bounds(area,key,width,height,density);
                check(a.left>=0&&a.top>=0&&a.right<=width&&a.bottom<=height&&a.width()>=0&&a.height()>=0,"simple pixel bounds");
                if(width>=320)check(a.width()>0&&a.height()>0,"simple keys visible");
                for(int j=i+1;j<region.size();j++){
                    KeyboardLayout.Bounds b=KeyboardLayout.bounds(area,region.get(j),width,height,density);
                    if(a.width()>0&&a.height()>0&&b.width()>0&&b.height()>0)check(!(a.left<b.right&&b.left<a.right&&a.top<b.bottom&&b.top<a.bottom),"simple overlap "+area+" "+i+"/"+j);
                }
            }
        }
        System.out.println("KeyboardLayoutTest: "+assertions+" assertions passed");
    }
}
