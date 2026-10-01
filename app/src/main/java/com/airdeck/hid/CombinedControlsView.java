package com.airdeck.hid;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Shared complete 87-key keyboard for keyboard-only and simultaneous keyboard/mouse modes. */
public final class CombinedControlsView extends LinearLayout {
    private static final int INK=0xFF192D29,MINT=0xFFDCECD9,WHITE=0xFFFFFFFF;
    private final HidController hid;
    private final boolean landscape;
    private final KeyboardView.Session keyboardSession;
    private final KeyboardView keyboard;
    private final List<MouseTouch> mouseTouches=new ArrayList<>();
    private TouchpadView pad;
    private int padMouseButtons,physicalMouseButtons;
    private boolean haptics=true;
    public CombinedControlsView(Context context,HidController controller){this(context,controller,false);}
    public CombinedControlsView(Context context,HidController controller,boolean keyboardOnly){
        super(context);hid=controller;
        landscape=getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE;
        setOrientation(landscape?HORIZONTAL:VERTICAL);setMotionEventSplittingEnabled(true);
        keyboardSession=new KeyboardView.Session(controller);
        KeyboardLayout.Area keyboardArea=keyboardOnly?(landscape?KeyboardLayout.Area.FULL:KeyboardLayout.Area.PORTRAIT_FULL)
                :(landscape?KeyboardLayout.Area.COMPACT_MAIN:KeyboardLayout.Area.PORTRAIT_MAIN);
        keyboard=new KeyboardView(context,keyboardSession,keyboardArea);
        if(keyboardOnly){addView(keyboard,new LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.MATCH_PARENT));return;}
        LinearLayout mouse=buildMouse();
        if(landscape){
            addView(keyboard,new LayoutParams(0,LayoutParams.MATCH_PARENT,.65f));
            LayoutParams m=new LayoutParams(0,LayoutParams.MATCH_PARENT,.35f);m.leftMargin=dp(10);addView(mouse,m);
        }else{
            LayoutParams m=new LayoutParams(LayoutParams.MATCH_PARENT,0,.42f);m.bottomMargin=dp(10);addView(mouse,m);
            addView(keyboard,new LayoutParams(LayoutParams.MATCH_PARENT,0,.58f));
        }
    }
    public void setSensitivity(float value){if(pad!=null)pad.setSensitivity(value);}
    public void setHapticFeedback(boolean value){haptics=value;keyboard.setHapticFeedback(value);if(pad!=null)pad.setHapticFeedback(value);}
    public void setKeyboardLeds(int leds){keyboard.setKeyboardLeds(leds);}
    public void refreshKeyboardLeds(){keyboard.refreshKeyboardLeds();}
    private LinearLayout buildMouse(){
        LinearLayout panel=column();
        KeyboardView editKeys=new KeyboardView(getContext(),keyboardSession,landscape?KeyboardLayout.Area.EDIT:KeyboardLayout.Area.EDIT_ROW);
        LayoutParams ep=landscape?new LayoutParams(LayoutParams.MATCH_PARENT,0,.18f):new LayoutParams(LayoutParams.MATCH_PARENT,dp(34));ep.bottomMargin=dp(6);panel.addView(editKeys,ep);
        pad=new TouchpadView(getContext());pad.setCompact(true);
        pad.setListener(new TouchpadView.Listener(){
            @Override public void mouseMove(int dx,int dy){hid.mouseMove(dx,dy);}
            @Override public void mouseScroll(int delta){hid.mouseScroll(delta);}
            @Override public void mouseButton(int button,boolean down){setMouseSource(true,button,down);}
            @Override public void mouseClick(int button){hid.mouseClick(button);}
        });
        panel.addView(pad,new LayoutParams(LayoutParams.MATCH_PARENT,0,landscape?.58f:1));
        LinearLayout clicks=row();TextView left=mouseKey("左键"),right=mouseKey("右键");
        left.setContentDescription("鼠标左键，按住并在触控板滑动可以拖拽");right.setContentDescription("鼠标右键");
        MouseTouch l=new MouseTouch(left,1),r=new MouseTouch(right,2);left.setOnTouchListener(l);right.setOnTouchListener(r);mouseTouches.add(l);mouseTouches.add(r);
        clicks.addView(left,new LayoutParams(0,LayoutParams.MATCH_PARENT,1));
        LayoutParams rp=new LayoutParams(0,LayoutParams.MATCH_PARENT,1);rp.leftMargin=dp(6);clicks.addView(right,rp);
        LayoutParams cp=new LayoutParams(LayoutParams.MATCH_PARENT,dp(landscape?32:36));cp.topMargin=dp(6);panel.addView(clicks,cp);
        KeyboardView arrows=new KeyboardView(getContext(),keyboardSession,landscape?KeyboardLayout.Area.ARROWS:KeyboardLayout.Area.ARROW_ROW);
        LayoutParams ap=landscape?new LayoutParams(LayoutParams.MATCH_PARENT,0,.24f):new LayoutParams(LayoutParams.MATCH_PARENT,dp(34));ap.topMargin=dp(6);panel.addView(arrows,ap);return panel;
    }
    private void setMouseSource(boolean fromPad,int mask,boolean down){
        if(down&&!hid.isConnected())return;
        int old=padMouseButtons|physicalMouseButtons;
        if(fromPad)padMouseButtons=down?padMouseButtons|mask:padMouseButtons&~mask;
        else physicalMouseButtons=down?physicalMouseButtons|mask:physicalMouseButtons&~mask;
        int now=padMouseButtons|physicalMouseButtons,changed=old^now;
        for(int bit=1;bit<=16;bit<<=1)if((changed&bit)!=0)hid.mouseButton(bit,(now&bit)!=0);
    }
    private final class MouseTouch implements OnTouchListener {
        final TextView view;final int mask;final Set<Integer> pointers=new HashSet<>();
        MouseTouch(TextView view,int mask){this.view=view;this.mask=mask;}
        @Override public boolean onTouch(View ignored,MotionEvent event){
            int action=event.getActionMasked(),index=event.getActionIndex();
            if(action==MotionEvent.ACTION_DOWN||action==MotionEvent.ACTION_POINTER_DOWN){
                boolean first=pointers.isEmpty();pointers.add(event.getPointerId(index));
                if(first){setMouseSource(false,mask,true);view.setPressed(true);view.setBackground(background(MINT));if(haptics)view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);}
            }else if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_POINTER_UP){pointers.remove(event.getPointerId(index));if(pointers.isEmpty()){setMouseSource(false,mask,false);reset();}
            }else if(action==MotionEvent.ACTION_CANCEL){setMouseSource(false,mask,false);reset();}
            return true;
        }
        void reset(){pointers.clear();view.setPressed(false);view.setBackground(background(WHITE));}
    }
    public void releaseAll(){keyboard.releaseAll();if(pad!=null)pad.releaseAll();for(MouseTouch mouse:mouseTouches)mouse.reset();padMouseButtons=physicalMouseButtons=0;hid.releaseAll();}
    @Override protected void onDetachedFromWindow(){releaseAll();super.onDetachedFromWindow();}
    private TextView mouseKey(String label){
        TextView v=new TextView(getContext());v.setText(label);v.setTextSize(12);v.setTextColor(INK);v.setTypeface(Typeface.create("sans-serif-medium",0));
        v.setGravity(Gravity.CENTER);v.setIncludeFontPadding(false);v.setPadding(0,0,0,0);v.setClickable(true);v.setFocusable(true);v.setBackground(background(WHITE));return v;
    }
    private RippleDrawable background(int color){
        GradientDrawable base=new GradientDrawable();base.setColor(color);base.setCornerRadius(dp(7));GradientDrawable mask=new GradientDrawable();mask.setColor(WHITE);mask.setCornerRadius(dp(7));
        return new RippleDrawable(ColorStateList.valueOf(0x22426D58),base,mask);
    }
    private LinearLayout row(){LinearLayout l=new LinearLayout(getContext());l.setOrientation(HORIZONTAL);l.setMotionEventSplittingEnabled(true);return l;}
    private LinearLayout column(){LinearLayout l=new LinearLayout(getContext());l.setOrientation(VERTICAL);l.setMotionEventSplittingEnabled(true);return l;}
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
