package com.airdeck.hid;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.SparseArray;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/** Multitouch controller. Presets change the surface, never the working HID report. */
public final class GamepadView extends View {
    public interface Listener {
        void onState(int buttons,int hat,int lx,int ly,int rx,int ry,int lt,int rt);
    }
    private static final int LEFT_STICK=20, RIGHT_STICK=21, DPAD=22, LEFT_TRIGGER=23, RIGHT_TRIGGER=24;
    private static final int INK=Color.rgb(26,36,33), MINT=Color.rgb(182,230,185);
    private static final int WHITE=Color.rgb(222,233,227), MUTED=Color.rgb(152,174,160);
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SparseArray<Finger> fingers=new SparseArray<>();
    private final RectF[] boxes=new RectF[10];
    private final float[] buttonX=new float[6], buttonY=new float[6], faceRadius=new float[6];
    private final RectF ltBox=new RectF(), rtBox=new RectF();
    private Listener listener;
    private GamepadPreset preset=GamepadPreset.XBOX;
    private boolean haptics=true, immersive, wide;
    private float leftX,leftY,rightX,rightY,stickR,dpadX,dpadY,dpadR,buttonR;
    private float scale=1, offsetX, offsetY, layoutW, layoutH;
    private int buttons,hat=8,lx,ly,rx,ry,lt,rt;

    private static final class Finger {
        final int control;
        float x,y;
        Finger(int control,float x,float y){this.control=control;this.x=x;this.y=y;}
    }
    public GamepadView(Context context){this(context,null);}
    public GamepadView(Context context,AttributeSet attrs){
        super(context,attrs);
        for(int i=0;i<boxes.length;i++)boxes[i]=new RectF();
        setClickable(true);setFocusable(true);updateDescription();
    }
    public void setListener(Listener value){listener=value;}
    public void setHapticFeedback(boolean enabled){haptics=enabled;}
    public GamepadPreset getPreset(){return preset;}
    public void setPreset(GamepadPreset value){
        if(value==null)throw new IllegalArgumentException("preset");
        if(preset==value)return;
        releaseAll();preset=value;updateDescription();layoutControls();invalidate();
    }
    public void setImmersive(boolean value){
        if(immersive==value)return;
        releaseAll();immersive=value;layoutControls();invalidate();
    }
    private void updateDescription(){
        setContentDescription(preset.label+" 游戏手柄。"+preset.description+"，支持多点触控。"+
                preset.mappingDescription());
    }

    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){
        super.onSizeChanged(w,h,oldw,oldh);releaseAll();layoutControls();
    }
    /** Uniform scaling keeps controls disjoint even in a short portrait preview. */
    private void layoutControls(){
        int w=getWidth(),h=getHeight();if(w<=0||h<=0)return;
        wide=w>h*1.35f;
        layoutW=wide?840:360;layoutH=wide?390:410;
        scale=Math.min(w/layoutW,h/layoutH);
        offsetX=(w-layoutW*scale)/2;offsetY=(h-layoutH*scale)/2;
        for(RectF box:boxes)box.setEmpty();ltBox.setEmpty();rtBox.setEmpty();
        float shoulderW=wide?68:50, shoulderH=wide?40:32;
        float pad=wide?24:14, top=wide?18:14, gap=wide?10:7;
        if(preset.hasShoulders()){
            boxes[4].set(pad,top,pad+shoulderW,top+shoulderH);
            boxes[5].set(layoutW-pad-shoulderW,top,layoutW-pad,top+shoulderH);
        }
        if(preset.hasLeftTrigger())ltBox.set(pad+shoulderW+gap,top,pad+2*shoulderW+gap,top+shoulderH);
        if(preset.hasRightTrigger())rtBox.set(layoutW-pad-2*shoulderW-gap,top,layoutW-pad-shoulderW-gap,top+shoulderH);
        for(int i=0;i<faceRadius.length;i++)faceRadius[i]=0;
        if(preset==GamepadPreset.ARCADE){layoutArcade();return;}
        if(preset.isClassic()){layoutClassic();return;}
        if(preset==GamepadPreset.N64){layoutN64();return;}
        if(wide){
            stickR=preset==GamepadPreset.PSP?58:64;
            dpadR=preset.hasOffsetSticks()?59:62;buttonR=preset==GamepadPreset.PSP?28:26;
            dpadX=preset.hasOffsetSticks()?281:126;dpadY=preset.hasOffsetSticks()?270:150;
            leftX=preset.hasOffsetSticks()?126:(preset==GamepadPreset.PSP?246:278);
            leftY=preset.hasOffsetSticks()?150:270;
            rightX=562;rightY=270;
            setFaceButtons(714,preset==GamepadPreset.PSP?174:150,preset==GamepadPreset.PSP?55:52);
            float menuY=preset==GamepadPreset.PSP?322:106;
            float menuW=preset==GamepadPreset.PSP?72:66;
            setCenteredBox(boxes[6],420-menuW/2-9,menuY,menuW,34);
            setCenteredBox(boxes[7],420+menuW/2+9,menuY,menuW,34);
            if(preset.hasStickClicks()){
                setCenteredBox(boxes[8],leftX,preset.hasOffsetSticks()?244:356,48,26);
                setCenteredBox(boxes[9],rightX,356,48,26);
            }
        }else{
            stickR=preset==GamepadPreset.PSP?53:51;dpadR=46;buttonR=20;
            dpadX=preset.hasOffsetSticks()?96:81;dpadY=preset.hasOffsetSticks()?294:132;
            leftX=preset.hasOffsetSticks()?81:96;leftY=preset.hasOffsetSticks()?132:294;
            rightX=264;rightY=294;
            setFaceButtons(278,132,35);
            if(preset==GamepadPreset.PSP){
                setCenteredBox(boxes[6],221,317,58,30);
                setCenteredBox(boxes[7],295,317,58,30);
            }else{
                setCenteredBox(boxes[6],147,214,52,30);
                setCenteredBox(boxes[7],213,214,52,30);
                setCenteredBox(boxes[8],leftX,preset.hasOffsetSticks()?214:368,44,24);
                setCenteredBox(boxes[9],rightX,368,44,24);
            }
        }
    }
    private void layoutArcade(){
        // Two gently arced rows provide six independent buttons, including chords.
        if(wide){
            leftX=200;leftY=212;stickR=91;
            setFaceButton(0,548,159,40);setFaceButton(1,643,141,40);setFaceButton(2,738,159,40);
            setFaceButton(3,557,258,40);setFaceButton(4,652,240,40);setFaceButton(5,747,258,40);
            setCenteredBox(boxes[6],369,333,74,34);setCenteredBox(boxes[7],465,333,74,34);
        }else{
            leftX=80;leftY=210;stickR=62;
            setFaceButton(0,198,178,24);setFaceButton(1,258,164,24);setFaceButton(2,318,178,24);
            setFaceButton(3,198,242,24);setFaceButton(4,258,228,24);setFaceButton(5,318,242,24);
            setCenteredBox(boxes[6],139,348,62,30);setCenteredBox(boxes[7],221,348,62,30);
        }
    }
    private void layoutClassic(){
        if(wide){
            dpadX=166;dpadY=205;dpadR=preset==GamepadPreset.SNES?74:80;
            if(preset==GamepadPreset.SNES){buttonR=33;setFaceButtons(689,202,65);}
            else if(preset==GamepadPreset.GBA){setFaceButton(0,724,177,45);setFaceButton(1,614,239,45);}
            else{setFaceButton(0,726,204,45);setFaceButton(1,613,204,45);}
            setCenteredBox(boxes[6],374,323,72,34);setCenteredBox(boxes[7],466,323,72,34);
            if(preset.hasShoulders()){
                setCenteredBox(boxes[4],111,39,112,40);setCenteredBox(boxes[5],729,39,112,40);
            }
        }else{
            dpadX=82;dpadY=203;dpadR=preset==GamepadPreset.SNES?54:56;
            if(preset==GamepadPreset.SNES){buttonR=22;setFaceButtons(274,203,42);}
            else if(preset==GamepadPreset.GBA){setFaceButton(0,294,175,30);setFaceButton(1,224,231,30);}
            else{setFaceButton(0,296,206,30);setFaceButton(1,223,206,30);}
            setCenteredBox(boxes[6],139,336,62,30);setCenteredBox(boxes[7],221,336,62,30);
        }
    }
    private void layoutN64(){
        if(wide){
            dpadX=126;dpadY=151;dpadR=57;
            leftX=278;leftY=275;stickR=67;
            setFaceButton(0,640,286,36);setFaceButton(1,551,234,31);
            setFaceButton(2,680,147,25);setFaceButton(3,776,147,25);
            setFaceButton(4,728,99,25);setFaceButton(5,728,195,25);
            setCenteredBox(boxes[7],420,143,68,34);
        }else{
            dpadX=72;dpadY=135;dpadR=45;
            leftX=96;leftY=295;stickR=54;
            setFaceButton(0,280,285,28);setFaceButton(1,211,231,25);
            setFaceButton(2,250,139,18);setFaceButton(3,320,139,18);
            setFaceButton(4,285,104,18);setFaceButton(5,285,174,18);
            setCenteredBox(boxes[7],221,358,66,30);
        }
    }
    private static void setCenteredBox(RectF r,float x,float y,float w,float h){r.set(x-w/2,y-h/2,x+w/2,y+h/2);}
    private void setFaceButton(int index,float x,float y,float radius){buttonX[index]=x;buttonY[index]=y;faceRadius[index]=radius;}
    private void setFaceButtons(float x,float y,float offset){
        setFaceButton(0,x,y+offset,buttonR);setFaceButton(1,x+offset,y,buttonR);
        setFaceButton(2,x-offset,y,buttonR);setFaceButton(3,x,y-offset,buttonR);
    }
    @Override protected void onDraw(Canvas c){
        super.onDraw(c);
        paint.setStyle(Paint.Style.FILL);paint.setColor(INK);
        float radius=immersive?0:24*getResources().getDisplayMetrics().density;
        c.drawRoundRect(new RectF(0,0,getWidth(),getHeight()),radius,radius,paint);
        if(getWidth()<=0||getHeight()<=0)return;
        c.save();c.translate(offsetX,offsetY);c.scale(scale,scale);
        if(preset.hasShoulders()){
            drawShoulder(c,boxes[4],preset.shoulderLabel(false,false),(buttons&(1<<4))!=0,false);
            drawShoulder(c,boxes[5],preset.shoulderLabel(true,false),(buttons&(1<<5))!=0,true);
        }
        if(preset.hasLeftTrigger())drawShoulder(c,ltBox,preset.shoulderLabel(false,true),lt>0,false);
        if(preset.hasRightTrigger())drawShoulder(c,rtBox,preset.shoulderLabel(true,true),rt>0,true);
        if(preset.hasDpad())drawDpad(c);
        if(preset.hasLeftStick())drawStick(c,leftX,leftY,lx,ly,false);
        if(preset.hasRightStick())drawStick(c,rightX,rightY,rx,ry,true);
        for(int i=0;i<preset.faceButtonCount();i++)drawFaceButton(c,i);
        if(preset.hasSelectButton())drawSmall(c,6,preset.menuLabel(false));
        drawSmall(c,7,preset.menuLabel(true));
        if(preset.hasStickClicks()){
            drawSmall(c,8,preset==GamepadPreset.SWITCH?"L PRESS":"L3");
            drawSmall(c,9,preset==GamepadPreset.SWITCH?"R PRESS":"R3");
        }
        if(!immersive)text(c,"多点触控 · 支持同时按下",layoutW/2,layoutH-12,10,Color.rgb(124,144,132),false);
        c.restore();
    }
    private int sideAccent(boolean right){
        if(preset==GamepadPreset.SWITCH)return right?Color.rgb(238,133,127):Color.rgb(119,196,216);
        if(preset==GamepadPreset.PS5)return Color.rgb(219,225,236);
        if(preset==GamepadPreset.ARCADE)return Color.rgb(239,190,127);
        if(preset==GamepadPreset.GBA)return Color.rgb(192,177,237);
        if(preset==GamepadPreset.NES)return Color.rgb(229,146,137);
        return MINT;
    }
    private int faceAccent(int index){
        if(preset.usesPlayStationSymbols())return new int[]{0xFF99BFEA,0xFFED969D,0xFFE1A6CC,0xFF8DD5BB}[index];
        if(preset==GamepadPreset.SWITCH)return WHITE;
        if(preset==GamepadPreset.GBA)return 0xFFC0B1ED;
        if(preset==GamepadPreset.NES)return 0xFFE59289;
        if(preset==GamepadPreset.SNES)return new int[]{0xFFE9D390,0xFFEB988E,0xFFAEDEA6,0xFF97BFE4}[index];
        if(preset==GamepadPreset.N64)return index==0?0xFFAEDEA6:(index==1?0xFF97BFE4:0xFFE9D390);
        if(preset==GamepadPreset.ARCADE)return index<3?0xFFB6E6B9:0xFFEFBE7F;
        return new int[]{0xFFAEDEA6,0xFFEB988E,0xFF97BFE4,0xFFE9D390}[index];
    }
    private void drawFaceButton(Canvas c,int index){
        boolean pressed=(buttons&(1<<preset.faceHidBit(index)))!=0;
        float x=buttonX[index],y=buttonY[index],r=faceRadius[index];int color=faceAccent(index);
        paint.setStyle(Paint.Style.FILL);paint.setColor(Color.argb(55,0,0,0));c.drawCircle(x,y+3,r,paint);
        paint.setColor(pressed?color:Color.rgb(48,62,54));c.drawCircle(x,y,r,paint);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.2f);paint.setColor(pressed?color:Color.rgb(77,97,84));c.drawCircle(x,y,r,paint);
        paint.setStyle(Paint.Style.FILL);
        if(preset.usesPlayStationSymbols())drawPlayStationSymbol(c,index,x,y,r*.39f,pressed?INK:color);
        else if(preset==GamepadPreset.N64&&index>=2)drawCButton(c,index,x,y,r,pressed?INK:color);
        else text(c,preset.faceLabel(index),x,y,preset==GamepadPreset.ARCADE?r*.46f:r*.74f,pressed?INK:color,true);
    }
    private void drawCButton(Canvas c,int index,float x,float y,float r,int color){
        // C arrows are separate buttons so diagonal combinations remain possible.
        text(c,"C",x,y-r*.30f,r*.44f,color,true);
        float cx=x,cy=y+r*.32f,size=r*.22f;
        c.save();c.rotate(index==2?270:index==3?90:index==5?180:0,cx,cy);
        Path arrow=new Path();arrow.moveTo(cx,cy-size);arrow.lineTo(cx-size,cy+size*.65f);arrow.lineTo(cx+size,cy+size*.65f);arrow.close();
        paint.setStyle(Paint.Style.FILL);paint.setColor(color);c.drawPath(arrow,paint);c.restore();
    }
    /** Paths avoid depending on a phone font for cross/circle/square/triangle glyphs. */
    private void drawPlayStationSymbol(Canvas c,int bit,float x,float y,float r,int color){
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2);paint.setColor(color);
        paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
        if(bit==0){c.drawLine(x-r,y-r,x+r,y+r,paint);c.drawLine(x+r,y-r,x-r,y+r,paint);}
        else if(bit==1)c.drawCircle(x,y,r*1.13f,paint);
        else if(bit==2)c.drawRect(x-r,y-r,x+r,y+r,paint);
        else{Path p=new Path();p.moveTo(x,y-r*1.25f);p.lineTo(x+r*1.16f,y+r*.92f);p.lineTo(x-r*1.16f,y+r*.92f);p.close();c.drawPath(p,paint);}
        paint.setStyle(Paint.Style.FILL);
    }
    private void drawShoulder(Canvas c,RectF r,String label,boolean pressed,boolean right){
        int accent=sideAccent(right);
        paint.setStyle(Paint.Style.FILL);paint.setColor(pressed?accent:Color.rgb(49,64,55));c.drawRoundRect(r,12,12,paint);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1);paint.setColor(pressed?accent:Color.rgb(73,93,79));c.drawRoundRect(r,12,12,paint);
        paint.setStyle(Paint.Style.FILL);paint.setColor(pressed?INK:accent);
        c.drawRoundRect(new RectF(r.left+13,r.top+5,r.right-13,r.top+7),1,1,paint);
        text(c,label,r.centerX(),r.centerY()+2,wide?14:12,pressed?INK:WHITE,true);
    }
    private void drawSmall(Canvas c,int bit,String label){
        RectF r=boxes[bit];boolean active=(buttons&(1<<bit))!=0;
        paint.setStyle(Paint.Style.FILL);paint.setColor(active?MINT:Color.rgb(41,54,46));c.drawRoundRect(r,9,9,paint);
        float font=preset==GamepadPreset.SWITCH&&bit<8?21:(label.length()>5?8.5f:10);
        text(c,label,r.centerX(),r.centerY(),font,active?INK:MUTED,true);
    }
    private void drawStick(Canvas c,float x,float y,int ax,int ay,boolean right){
        int accent=sideAccent(right);
        paint.setStyle(Paint.Style.FILL);paint.setColor(Color.rgb(31,43,37));c.drawCircle(x,y,stickR,paint);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.2f);paint.setColor(Color.rgb(71,88,77));c.drawCircle(x,y,stickR,paint);
        paint.setColor(Color.rgb(43,58,48));c.drawCircle(x,y,stickR*.75f,paint);
        for(int i=0;i<4;i++){
            c.save();c.rotate(i*90,x,y);paint.setColor(Color.rgb(81,100,87));c.drawLine(x,y-stickR*.89f,x,y-stickR*.96f,paint);c.restore();
        }
        float px=x+ax/127f*stickR*.50f,py=y+ay/127f*stickR*.50f;
        boolean active=ax!=0||ay!=0;
        paint.setStyle(Paint.Style.FILL);paint.setColor(Color.argb(80,0,0,0));c.drawCircle(px,py+4,stickR*.47f,paint);
        paint.setColor(active?accent:Color.rgb(60,79,67));c.drawCircle(px,py,stickR*.47f,paint);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.2f);paint.setColor(active?WHITE:accent);c.drawCircle(px,py,stickR*.47f,paint);
        paint.setColor(active?Color.argb(48,26,36,33):Color.rgb(75,95,81));c.drawCircle(px,py,stickR*.36f,paint);
        paint.setStyle(Paint.Style.FILL);paint.setColor(active?INK:Color.rgb(136,160,144));c.drawCircle(px,py,2.2f,paint);
    }
    private void drawDpad(Canvas c){
        float arm=dpadR*.36f;
        paint.setStyle(Paint.Style.FILL);paint.setColor(Color.rgb(44,59,49));
        c.drawRoundRect(new RectF(dpadX-arm,dpadY-dpadR,dpadX+arm,dpadY+dpadR),8,8,paint);
        c.drawRoundRect(new RectF(dpadX-dpadR,dpadY-arm,dpadX+dpadR,dpadY+arm),8,8,paint);
        for(int direction=0;direction<4;direction++){
            boolean on=hat!=8&&(hat==direction*2||hat==(direction*2+1)%8||hat==(direction*2+7)%8);
            c.save();c.rotate(direction*90,dpadX,dpadY);
            if(on){paint.setColor(sideAccent(false));c.drawRoundRect(new RectF(dpadX-arm+2,dpadY-dpadR+2,dpadX+arm-2,dpadY-arm*.45f),6,6,paint);}
            float arrowY=dpadY-dpadR*.67f,arrowR=4.5f;
            Path arrow=new Path();arrow.moveTo(dpadX,arrowY-arrowR);arrow.lineTo(dpadX-arrowR,arrowY+arrowR*.5f);arrow.lineTo(dpadX+arrowR,arrowY+arrowR*.5f);arrow.close();
            paint.setColor(on?INK:WHITE);c.drawPath(arrow,paint);c.restore();
        }
        paint.setColor(Color.rgb(33,46,39));c.drawCircle(dpadX,dpadY,arm*.45f,paint);
    }
    private void text(Canvas c,String label,float x,float y,float size,int color,boolean bold){
        paint.setStyle(Paint.Style.FILL);paint.setTextAlign(Paint.Align.CENTER);paint.setTextSize(size);paint.setColor(color);
        paint.setTypeface(android.graphics.Typeface.create(bold?"sans-serif-medium":"sans-serif",0));
        Paint.FontMetrics fm=paint.getFontMetrics();c.drawText(label,x,y-(fm.ascent+fm.descent)/2,paint);
    }
    private float touchX(MotionEvent e,int i){return(e.getX(i)-offsetX)/scale;}
    private float touchY(MotionEvent e,int i){return(e.getY(i)-offsetY)/scale;}
    @Override public boolean onTouchEvent(MotionEvent e){
        if(!isEnabled())return false;
        int action=e.getActionMasked(),index=e.getActionIndex();
        if(action==MotionEvent.ACTION_DOWN||action==MotionEvent.ACTION_POINTER_DOWN){
            if(action==MotionEvent.ACTION_DOWN){releaseAll();if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);}
            float x=touchX(e,index),y=touchY(e,index);int control=hit(x,y);
            if(control>=0&&!owned(control)){
                fingers.put(e.getPointerId(index),new Finger(control,x,y));
                if(haptics)performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            }
            updateAll(e);dispatchState(false);
        }else if(action==MotionEvent.ACTION_MOVE){updateAll(e);dispatchState(false);
        }else if(action==MotionEvent.ACTION_POINTER_UP||action==MotionEvent.ACTION_UP){
            updateAll(e);fingers.remove(e.getPointerId(index));dispatchState(false);
            if(action==MotionEvent.ACTION_UP){performClick();if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);}
        }else if(action==MotionEvent.ACTION_CANCEL){releaseAll();if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);}
        invalidate();return true;
    }
    private boolean owned(int control){
        for(int i=0;i<fingers.size();i++)if(fingers.valueAt(i).control==control)return true;return false;
    }
    private void updateAll(MotionEvent e){
        for(int i=0;i<e.getPointerCount();i++){
            Finger f=fingers.get(e.getPointerId(i));if(f!=null){f.x=touchX(e,i);f.y=touchY(e,i);}
        }
    }
    private boolean buttonVisible(int bit){
        if(bit==4||bit==5)return preset.hasShoulders();
        if(bit==6)return preset.hasSelectButton();
        if(bit==7)return true;
        return (bit==8||bit==9)&&preset.hasStickClicks();
    }
    // Extra face controls need independent IDs: their HID bits can also be shoulder/stick-click bits.
    private static int faceControl(int index){return index<4?index:index+6;}
    private int faceIndex(int control){
        int index=control>=0&&control<4?control:(control==10||control==11?control-6:-1);
        return index>=0&&index<preset.faceButtonCount()?index:-1;
    }
    private int hit(float x,float y){
        for(int i=4;i<10;i++)if(buttonVisible(i)&&expandedContains(boxes[i],x,y,5))return i;
        if(preset.hasLeftTrigger()&&expandedContains(ltBox,x,y,4))return LEFT_TRIGGER;
        if(preset.hasRightTrigger()&&expandedContains(rtBox,x,y,4))return RIGHT_TRIGGER;
        for(int i=0;i<preset.faceButtonCount();i++)if(distance(x-buttonX[i],y-buttonY[i])<=faceRadius[i]+5)return faceControl(i);
        if(preset.hasLeftStick()&&distance(x-leftX,y-leftY)<=stickR+8)return LEFT_STICK;
        if(preset.hasRightStick()&&distance(x-rightX,y-rightY)<=stickR+8)return RIGHT_STICK;
        if(preset.hasDpad()&&Math.abs(x-dpadX)<=dpadR+6&&Math.abs(y-dpadY)<=dpadR+6)return DPAD;
        return -1;
    }
    private static boolean expandedContains(RectF r,float x,float y,float extra){
        return!r.isEmpty()&&x>=r.left-extra&&x<=r.right+extra&&y>=r.top-extra&&y<=r.bottom+extra;
    }
    private static float distance(float x,float y){return(float)Math.sqrt(x*x+y*y);}
    private void dispatchState(boolean force){
        int b=0,h=8,lxValue=0,lyValue=0,rxValue=0,ryValue=0,ltValue=0,rtValue=0;
        for(int i=0;i<fingers.size();i++){
            Finger f=fingers.valueAt(i);int control=f.control,face=faceIndex(control);
            if(face>=0){
                if(distance(f.x-buttonX[face],f.y-buttonY[face])<faceRadius[face]+22)b|=1<<preset.faceHidBit(face);
            }else if(control>=4&&control<10&&buttonVisible(control)){
                if(expandedContains(boxes[control],f.x,f.y,18))b|=1<<control;
            }else if(control==LEFT_TRIGGER&&preset.hasLeftTrigger()){
                if(expandedContains(ltBox,f.x,f.y,20))ltValue=255;
            }else if(control==RIGHT_TRIGGER&&preset.hasRightTrigger()){
                if(expandedContains(rtBox,f.x,f.y,20))rtValue=255;
            }else if(control==DPAD&&preset.hasDpad()){
                float dx=f.x-dpadX,dy=f.y-dpadY;
                if(distance(dx,dy)>dpadR*.20f){double angle=Math.atan2(dx,-dy);h=((int)Math.round(angle/(Math.PI/4))+8)%8;}
            }else if((control==LEFT_STICK&&preset.hasLeftStick())||(control==RIGHT_STICK&&preset.hasRightStick())){
                float x=f.x-(control==LEFT_STICK?leftX:rightX),y=f.y-(control==LEFT_STICK?leftY:rightY);
                float magnitude=distance(x,y),limit=stickR*.82f;int ax=0,ay=0;
                if(magnitude>limit*.09f){
                    float value=Math.min(1f,(magnitude/limit-.09f)/.91f);
                    ax=Math.round(x/magnitude*value*127);ay=Math.round(y/magnitude*value*127);
                }
                if(control==LEFT_STICK){lxValue=ax;lyValue=ay;}else{rxValue=ax;ryValue=ay;}
            }
        }
        boolean changed=buttons!=b||hat!=h||lx!=lxValue||ly!=lyValue||rx!=rxValue||ry!=ryValue||lt!=ltValue||rt!=rtValue;
        buttons=b;hat=h;lx=lxValue;ly=lyValue;rx=rxValue;ry=ryValue;lt=ltValue;rt=rtValue;
        if(listener!=null&&(changed||force))listener.onState(buttons,hat,lx,ly,rx,ry,lt,rt);
        invalidate();
    }
    public void releaseAll(){fingers.clear();dispatchState(true);}
    @Override public boolean performClick(){super.performClick();return true;}
    @Override protected void onDetachedFromWindow(){releaseAll();super.onDetachedFromWindow();}
}
