package com.airdeck.hid;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Native keycaps with shared modifier ownership across independently placed keyboard regions. */
public final class KeyboardView extends ViewGroup {
    private static final int INK=0xFF192D29,GREEN=0xFF426D58,MINT=0xFFDCECD9,WHITE=0xFFFFFFFF;
    public static final class Session {
        final HidController hid;
        final KeyboardLayout.State state=new KeyboardLayout.State();
        final List<KeyboardView> views=new ArrayList<>();
        final Map<Integer,Integer> owners=new HashMap<>();
        boolean haptics=true;
        public Session(HidController hid){this.hid=hid;state.setKeyboardLeds(hid.getKeyboardLeds());}
        public void setHapticFeedback(boolean enabled){haptics=enabled;}
        public void setKeyboardLeds(int leds){state.setKeyboardLeds(leds);refresh();}
        public void refreshKeyboardLeds(){setKeyboardLeds(hid.getKeyboardLeds());}
        void refresh(){for(KeyboardView view:views)view.refreshLabels();}
        void press(KeyboardView.KeyCap cap){
            if(cap.held)return;cap.held=true;
            Integer old=owners.get(cap.spec.usage);int count=old==null?0:old;
            owners.put(cap.spec.usage,count+1);
            if(count==0){state.setPressed(cap.spec.usage,true);hid.keyDown(cap.spec.usage);}
            refresh();if(haptics)cap.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        }
        void release(KeyboardView.KeyCap cap){
            if(!cap.held)return;cap.held=false;
            Integer count=owners.get(cap.spec.usage);
            if(count!=null&&count>1)owners.put(cap.spec.usage,count-1);
            else{owners.remove(cap.spec.usage);state.setPressed(cap.spec.usage,false);hid.keyUp(cap.spec.usage);}
            refresh();
        }
        public void releaseAll(){
            Set<Integer> owned=state.pressedUsages();state.releaseAll();owners.clear();
            for(KeyboardView view:views)for(KeyboardView.KeyCap cap:view.caps){cap.pointers.clear();cap.held=false;}
            for(int usage:owned)hid.keyUp(usage);refresh();
        }
    }
    private final Session session;
    private final KeyboardLayout.Area area;
    private final List<KeyCap> caps=new ArrayList<>();
    public KeyboardView(Context context,HidController controller){this(context,new Session(controller),KeyboardLayout.Area.FULL);}
    public KeyboardView(Context context,Session session,KeyboardLayout.Area area){
        super(context);this.session=session;this.area=area;setMotionEventSplittingEnabled(true);setClipChildren(false);setClipToPadding(false);
        for(KeyboardLayout.Key spec:KeyboardLayout.keys(area)){KeyCap cap=new KeyCap(context,spec);caps.add(cap);addView(cap,new LayoutParams(0,0));}
        session.views.add(this);refreshLabels();
    }
    public void setHapticFeedback(boolean enabled){session.setHapticFeedback(enabled);}
    public void setKeyboardLeds(int leds){session.setKeyboardLeds(leds);}
    public void refreshKeyboardLeds(){session.refreshKeyboardLeds();}
    public void releaseAll(){session.releaseAll();}
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){super.onSizeChanged(w,h,oldw,oldh);if(oldw>0&&(w!=oldw||h!=oldh))releaseAll();}
    @Override protected void onMeasure(int widthSpec,int heightSpec){
        int width=resolveSize(dp(area.width*40),widthSpec),height=resolveSize(dp(area.height*45),heightSpec);setMeasuredDimension(width,height);
        for(KeyCap cap:caps){KeyboardLayout.Bounds b=KeyboardLayout.bounds(area,cap.spec,width,height,getResources().getDisplayMetrics().density);
            cap.measure(MeasureSpec.makeMeasureSpec(b.width(),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(b.height(),MeasureSpec.EXACTLY));cap.updateTextSize(b.width(),b.height());}
    }
    @Override protected void onLayout(boolean changed,int left,int top,int right,int bottom){
        for(KeyCap cap:caps){KeyboardLayout.Bounds b=KeyboardLayout.bounds(area,cap.spec,right-left,bottom-top,getResources().getDisplayMetrics().density);cap.layout(b.left,b.top,b.right,b.bottom);}
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event){
        if(event.getActionMasked()==MotionEvent.ACTION_DOWN&&getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);
        boolean handled=super.dispatchTouchEvent(event);
        if((event.getActionMasked()==MotionEvent.ACTION_UP||event.getActionMasked()==MotionEvent.ACTION_CANCEL)&&getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);return handled;
    }
    private void refreshLabels(){
        for(KeyCap cap:caps){
            String label=session.state.label(cap.spec);cap.setText(label);
            cap.setContentDescription(cap.spec.description+"；"+label+(cap.spec.shifted==null?"":"；Shift "+cap.spec.shifted)+(cap.spec.isModifier()?"，按住生效，松开释放":""));
            cap.showState(cap.held,cap.spec.usage==57&&session.state.caps()||cap.spec.usage==71&&session.state.scrollLock());
            if(cap.getMeasuredWidth()>0)cap.updateTextSize(cap.getMeasuredWidth(),cap.getMeasuredHeight());
        }
    }
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();if(!session.views.contains(this))session.views.add(this);refreshLabels();}
    @Override protected void onDetachedFromWindow(){for(KeyCap cap:caps){cap.pointers.clear();session.release(cap);}session.views.remove(this);super.onDetachedFromWindow();}
    private final class KeyCap extends TextView implements OnTouchListener {
        final KeyboardLayout.Key spec;final Set<Integer> pointers=new HashSet<>();final GradientDrawable face=new GradientDrawable();
        final Paint legendPaint=new Paint(Paint.ANTI_ALIAS_FLAG);boolean held;
        KeyCap(Context context,KeyboardLayout.Key spec){
            super(context);this.spec=spec;setGravity(Gravity.CENTER);setIncludeFontPadding(false);setPadding(dp(1),0,dp(1),0);
            setTypeface(Typeface.create("sans-serif-medium",0));setSingleLine(true);setClickable(true);setFocusable(true);setMinWidth(0);setMinHeight(0);
            face.setCornerRadius(dp(5));setBackground(face);setOnTouchListener(this);
            legendPaint.setTypeface(Typeface.create("sans-serif",0));legendPaint.setTextAlign(Paint.Align.RIGHT);
            setOnClickListener(v->{if(!spec.isModifier()){session.hid.tapKey(spec.usage,0);if(session.haptics)performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);}});
        }
        @Override public CharSequence getAccessibilityClassName(){return "android.widget.Button";}
        @Override public boolean onTouch(View view,MotionEvent event){
            int action=event.getActionMasked(),index=event.getActionIndex();
            if(action==MotionEvent.ACTION_DOWN||action==MotionEvent.ACTION_POINTER_DOWN){boolean first=pointers.isEmpty();pointers.add(event.getPointerId(index));if(first)session.press(this);
            }else if(action==MotionEvent.ACTION_MOVE){for(int i=0;i<event.getPointerCount();i++)if(event.getX(i)<0||event.getY(i)<0||event.getX(i)>=getWidth()||event.getY(i)>=getHeight())pointers.remove(event.getPointerId(i));if(pointers.isEmpty())session.release(this);
            }else if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_POINTER_UP){pointers.remove(event.getPointerId(index));if(pointers.isEmpty())session.release(this);
            }else if(action==MotionEvent.ACTION_CANCEL){pointers.clear();session.release(this);}return true;
        }
        @Override protected void onDraw(Canvas canvas){
            // TextView applies text-layout clipping/transforms. The corner legend uses
            // the original keycap coordinates and must not inherit that text-only clip.
            int originalCanvas=canvas.save();
            try{super.onDraw(canvas);}finally{canvas.restoreToCount(originalCanvas);}
            if(spec.shifted!=null&&getWidth()>0&&getHeight()>0){
                float density=getResources().getDisplayMetrics().density;
                legendPaint.setTextSize(Math.max(4*density,Math.min(8*density,Math.min(getWidth()*.23f,getHeight()*.20f))));
                legendPaint.setColor(held?GREEN:0xFF819084);Paint.FontMetrics fm=legendPaint.getFontMetrics();
                canvas.drawText(spec.shifted,getWidth()-dp(3),dp(2)-fm.ascent,legendPaint);
            }
        }
        void showState(boolean pressed,boolean locked){setPressed(pressed);setSelected(locked);face.setColor(pressed?MINT:locked?0xFFEBF3E8:WHITE);face.setStroke(dp(1),pressed?0xFFABCAA5:locked?0xFFABC6A5:0xFFE3E9E1);setTextColor(pressed||locked?GREEN:INK);invalidate();}
        void updateTextSize(int width,int height){
            float density=getResources().getDisplayMetrics().density;int length=Math.max(1,getText().length());
            float size=Math.min(15*density,height*.33f);size=Math.min(size,(width-dp(4))/(length*.61f));setTextSize(TypedValue.COMPLEX_UNIT_PX,Math.max(5*density,size));
        }
    }
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
