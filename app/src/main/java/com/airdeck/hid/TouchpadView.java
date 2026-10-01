package com.airdeck.hid;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.SparseArray;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/** Relative mouse surface. Button numbers are HID masks: left=1, right=2. */
public final class TouchpadView extends View {
    public interface Listener {
        void mouseMove(int dx, int dy);
        void mouseButton(int button, boolean down);
        void mouseScroll(int delta);
        default void mouseClick(int button) {
            mouseButton(button,true);
            mouseButton(button,false);
        }
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SparseArray<PointF> starts = new SparseArray<>();
    private final SparseArray<PointF> touches = new SparseArray<>();
    private Listener listener;
    private float sensitivity = 1.4f;
    private boolean haptics = true;
    private boolean compact;
    private int primaryId = -1, maxPointers;
    private long startedAt, lastTapAt;
    private float lastX, lastY, twoLastY, remainderX, remainderY, scrollRemainder;
    private float lastTapX, lastTapY;
    private boolean moved, multiple, dragging, twoTracking;

    public TouchpadView(Context context) { this(context, null); }
    public TouchpadView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setClickable(true);
        setFocusable(true);
        setContentDescription("触控板。单指移动，轻触左键，双指轻触右键，双指上下滑动滚动，双击并按住拖拽。");
    }

    public void setListener(Listener value) { listener = value; }
    public void setSensitivity(float value) { sensitivity = Math.max(0.2f, Math.min(4f, value)); }
    public void setHapticFeedback(boolean enabled) { haptics = enabled; }
    public void setCompact(boolean enabled) { compact=enabled;invalidate(); }
    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth(), h = getHeight(), inset = dp(1);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(240, 246, 242));
        c.drawRoundRect(new RectF(inset, inset, w-inset, h-inset), dp(24), dp(24), paint);
        c.save();
        c.clipRect(dp(12), dp(12), w-dp(12), h-dp(12));
        paint.setColor(Color.rgb(209, 223, 215));
        for (float x = dp(19); x < w; x += dp(18)) {
            for (float y = dp(19); y < h; y += dp(18)) c.drawCircle(x, y, dp(0.8f), paint);
        }
        c.restore();
        if(compact){
            float cx=w/2f,cy=h/2f-dp(8);
            if(h>=dp(96)){
                paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1.6f));paint.setColor(Color.rgb(101,137,113));
                c.drawRoundRect(new RectF(cx-dp(8),cy-dp(22),cx+dp(8),cy+dp(2)),dp(7),dp(7),paint);
                c.drawLine(cx,cy-dp(17),cx,cy-dp(11),paint);
                paint.setStyle(Paint.Style.FILL);
                drawText(c,dragging?"正在拖拽":"滑动移动 · 轻点左键",cx,cy+dp(25),11,Color.rgb(88,118,99),false);
            }else{
                drawText(c,dragging?"正在拖拽":"滑动移动 · 轻点左键",cx,h/2f+dp(4),11,Color.rgb(88,118,99),false);
            }
        }else{
        float centerX=w/2f, centerY=h/2f-dp(28);
        paint.setColor(Color.rgb(225, 237, 229));
        c.drawCircle(centerX, centerY-dp(23), dp(35), paint);
        paint.setColor(Color.rgb(78, 115, 94));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.8f));
        c.drawRoundRect(new RectF(centerX-dp(11),centerY-dp(40),centerX+dp(11),centerY-dp(6)),dp(10),dp(10),paint);
        c.drawLine(centerX,centerY-dp(34),centerX,centerY-dp(26),paint);
        paint.setStyle(Paint.Style.FILL);
        drawText(c, dragging ? "正在拖拽" : "指尖移动，自在控制", centerX, centerY+dp(36), 17, Color.rgb(65,87,74), true);
        drawText(c, "单指移动 · 轻触点击", centerX, centerY+dp(60), 12, Color.rgb(113,133,121), false);
        if (h > dp(220)) {
            float footY=h-dp(31);
            drawText(c,"双指轻触右键",w*0.27f,footY,10,Color.rgb(122,142,130),false);
            drawText(c,"双指滑动滚动",w*0.73f,footY,10,Color.rgb(122,142,130),false);
            paint.setColor(Color.rgb(192,211,200));
            c.drawCircle(w/2f,footY-dp(3),dp(1.5f),paint);
        }
        }
        for (int i=0;i<touches.size();i++) {
            PointF p=touches.valueAt(i);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(38,64,145,100));
            c.drawCircle(p.x,p.y,dp(25),paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1.5f));
            paint.setColor(Color.argb(180,91,152,115));
            c.drawCircle(p.x,p.y,dp(18),paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(69,127,89));
            c.drawCircle(p.x,p.y,dp(3),paint);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(Color.rgb(222,233,225));
        c.drawRoundRect(new RectF(inset,inset,w-inset,h-inset),dp(24),dp(24),paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawText(Canvas c,String value,float x,float y,float size,int color,boolean bold) {
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(dp(size));
        paint.setColor(color);
        paint.setTypeface(bold ? android.graphics.Typeface.create("sans-serif-medium",0) : android.graphics.Typeface.create("sans-serif",0));
        c.drawText(value,x,y,paint);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        int action=e.getActionMasked(), index=e.getActionIndex();
        if (!isEnabled()) return false;
        // A lifecycle release invalidates the entire old gesture, including a late ACTION_UP.
        if(primaryId<0 && action!=MotionEvent.ACTION_DOWN && action!=MotionEvent.ACTION_CANCEL) return true;
        if (action==MotionEvent.ACTION_DOWN) {
            if(dragging && listener!=null) listener.mouseButton(1,false);
            dragging=false;
            clearGesture();
            if(getParent()!=null) getParent().requestDisallowInterceptTouchEvent(true);
            primaryId=e.getPointerId(0);
            startedAt=e.getEventTime();
            lastX=e.getX(); lastY=e.getY();
            maxPointers=1;
            starts.put(primaryId,new PointF(lastX,lastY));
            touches.put(primaryId,new PointF(lastX,lastY));
            if (lastTapAt>0 && startedAt-lastTapAt<300 && distance(lastX-lastTapX,lastY-lastTapY)<dp(32)) {
                dragging=true;
                if (listener!=null) listener.mouseButton(1,true);
                tick();
                lastTapAt=0;
            }
        } else if (action==MotionEvent.ACTION_POINTER_DOWN) {
            int id=e.getPointerId(index);
            starts.put(id,new PointF(e.getX(index),e.getY(index)));
            maxPointers=Math.max(maxPointers,e.getPointerCount());
            multiple=true;
            lastTapAt=0;
            if(dragging) { dragging=false; if(listener!=null) listener.mouseButton(1,false); }
            twoLastY=centroidY(e,-1);
            twoTracking=e.getPointerCount()==2;
            scrollRemainder=0;
        } else if(action==MotionEvent.ACTION_MOVE) {
            updateMoved(e);
            if (e.getPointerCount()==2 && twoTracking) {
                float y=centroidY(e,-1);
                scrollRemainder+=(twoLastY-y)/dp(15);
                twoLastY=y;
                int steps=(int)scrollRemainder;
                if(steps!=0) { if(listener!=null) listener.mouseScroll(steps); scrollRemainder-=steps; moved=true; }
            } else if(!multiple) {
                int at=e.findPointerIndex(primaryId);
                if(at>=0) {
                    float x=e.getX(at), y=e.getY(at);
                    remainderX+=(x-lastX)*sensitivity;
                    remainderY+=(y-lastY)*sensitivity;
                    int dx=(int)remainderX, dy=(int)remainderY;
                    remainderX-=dx; remainderY-=dy;
                    lastX=x; lastY=y;
                    if(listener!=null && (dx!=0 || dy!=0)) listener.mouseMove(dx,dy);
                }
            }
        } else if(action==MotionEvent.ACTION_POINTER_UP) {
            updateMoved(e);
            twoTracking=false;
        } else if(action==MotionEvent.ACTION_UP) {
            updateMoved(e);
            boolean wasDragging=dragging;
            if(dragging) { dragging=false; if(listener!=null) listener.mouseButton(1,false); }
            if(!wasDragging && !moved && e.getEventTime()-startedAt<300) {
                if(maxPointers<=2) {
                    clickButton(multiple ? 2 : 1);
                    performClick();
                    if(!multiple) { lastTapAt=e.getEventTime(); lastTapX=e.getX();lastTapY=e.getY(); }
                }
            } else lastTapAt=0;
            clearGesture();
            if(getParent()!=null) getParent().requestDisallowInterceptTouchEvent(false);
        } else if(action==MotionEvent.ACTION_CANCEL) {
            releaseAll();
            lastTapAt=0;
            if(getParent()!=null) getParent().requestDisallowInterceptTouchEvent(false);
        }
        if(action!=MotionEvent.ACTION_UP && action!=MotionEvent.ACTION_CANCEL) {
            touches.clear();
            for(int i=0;i<e.getPointerCount();i++) {
                if(action==MotionEvent.ACTION_POINTER_UP && i==index) continue;
                touches.put(e.getPointerId(i),new PointF(e.getX(i),e.getY(i)));
            }
        }
        invalidate();
        return true;
    }

    private void updateMoved(MotionEvent e) {
        for(int i=0;i<e.getPointerCount();i++) {
            PointF start=starts.get(e.getPointerId(i));
            if(start!=null && distance(e.getX(i)-start.x,e.getY(i)-start.y)>dp(9)) moved=true;
        }
    }
    private float centroidY(MotionEvent e,int excluded) {
        float total=0;int count=0;
        for(int i=0;i<e.getPointerCount();i++) if(i!=excluded) { total+=e.getY(i); count++; }
        return count==0?0:total/count;
    }
    private static float distance(float x,float y) { return (float)Math.sqrt(x*x+y*y); }
    private void clickButton(int button) {
        if(listener!=null) listener.mouseClick(button);
        tick();
    }
    private void tick() { if(haptics) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); }
    private void clearGesture() {
        touches.clear(); starts.clear(); primaryId=-1; maxPointers=0;
        moved=false;multiple=false;twoTracking=false;remainderX=0;remainderY=0;scrollRemainder=0;
    }
    public void releaseAll() {
        if(dragging && listener!=null) listener.mouseButton(1,false);
        dragging=false;
        lastTapAt=0;
        clearGesture();
        invalidate();
    }
    @Override public boolean performClick() { super.performClick();return true; }
    @Override protected void onDetachedFromWindow() { releaseAll();super.onDetachedFromWindow(); }
}
