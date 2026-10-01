package com.airdeck.hid;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.input.InputManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Foreground-only receiver diagnostic. It never starts the Bluetooth HID Device profile. */
public final class InputProbeActivity extends Activity {
    private static final String TAG="AirDeckProbe";
    private static final int[] AXES={MotionEvent.AXIS_X,MotionEvent.AXIS_Y,MotionEvent.AXIS_Z,
            MotionEvent.AXIS_RZ,MotionEvent.AXIS_LTRIGGER,MotionEvent.AXIS_RTRIGGER,
            MotionEvent.AXIS_HAT_X,MotionEvent.AXIS_HAT_Y};
    private static final String[] AXIS_NAMES={"X","Y","Z","RZ","LT","RT","HAT_X","HAT_Y"};
    private static final int MAX_LOG_BYTES=262144;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ArrayDeque<String> history=new ArrayDeque<>();
    private TextView counters,events,devices;
    private InputManager inputManager;
    private BufferedWriter logWriter;
    private File logFile;
    private boolean active,listenerRegistered,uiPending;
    private int keyDowns,keyUps,mouseEvents,joystickEvents,deviceCount;
    private long logBytes,lastMouseAt,lastAxesAt;
    private int lastMouseAction=-1,lastMouseButtons=-1,lastMouseDevice=Integer.MIN_VALUE;
    private final float[] lastAxes=new float[AXES.length];
    private boolean haveAxes,lastAxesNeutral=true;
    private int lastAxisDevice=Integer.MIN_VALUE;
    private String pendingAxes,diskError="";

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(0xFFF4F6F3);getWindow().setNavigationBarColor(0xFFF4F6F3);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        logFile=new File(getFilesDir(),"probe-events.txt");
        inputManager=(InputManager)getSystemService(Context.INPUT_SERVICE);
        buildUi();
        clearProbe();
    }

    private void buildUi(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18),dp(14),dp(18),dp(14));root.setBackgroundColor(0xFFF4F6F3);
        TextView title=text("接收端输入测试",22,false);title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);root.addView(title);
        TextView hint=text("在发送端操作键盘、鼠标或手柄。这里只记录当前测试窗口收到的按键码与运动数据，不记录输入文字。",12,false);
        hint.setTextColor(0xFF66766D);hint.setPadding(0,dp(8),0,dp(10));root.addView(hint);
        counters=text("等待输入…",13,true);counters.setPadding(dp(12),dp(12),dp(12),dp(12));counters.setBackground(round(0xFFDCECD9));root.addView(counters);
        Button clear=new Button(this);clear.setText("清空计数与事件");clear.setAllCaps(false);clear.setTextSize(13);
        clear.setOnClickListener(v->clearProbe());root.addView(clear,new LinearLayout.LayoutParams(-1,dp(44)));
        TextView eventLabel=text("最近事件（最多 12 条）",13,false);eventLabel.setPadding(0,dp(5),0,dp(6));root.addView(eventLabel);
        events=text("等待实际输入设备事件",11,true);events.setPadding(dp(10),dp(8),dp(10),dp(8));events.setBackground(round(Color.WHITE));
        ScrollView eventScroll=new ScrollView(this);eventScroll.setFillViewport(true);eventScroll.addView(events);
        root.addView(eventScroll,new LinearLayout.LayoutParams(-1,0,1.1f));
        TextView deviceLabel=text("系统识别的输入设备",13,false);deviceLabel.setPadding(0,dp(12),0,dp(6));root.addView(deviceLabel);
        devices=text("正在读取…",10,true);devices.setPadding(dp(10),dp(8),dp(10),dp(8));devices.setBackground(round(Color.WHITE));
        ScrollView deviceScroll=new ScrollView(this);deviceScroll.addView(devices);
        root.addView(deviceScroll,new LinearLayout.LayoutParams(-1,0,1));
        TextView foot=text("日志：probe-events.txt / probe-devices.txt · Android 返回键退出",10,false);
        foot.setTextColor(0xFF66766D);foot.setPadding(0,dp(8),0,0);root.addView(foot);
        setContentView(root);
    }

    @Override protected void onResume(){
        super.onResume();active=true;openLog(true);
        if(inputManager!=null&&!listenerRegistered){inputManager.registerInputDeviceListener(deviceListener,handler);listenerRegistered=true;}
        refreshDevices();record("INFO foreground receiver probe ready");
    }
    @Override protected void onPause(){
        flushPendingAxes.run();active=false;
        handler.removeCallbacks(flushPendingAxes);handler.removeCallbacks(renderUi);uiPending=false;
        if(inputManager!=null&&listenerRegistered){inputManager.unregisterInputDeviceListener(deviceListener);listenerRegistered=false;}
        closeLog();super.onPause();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event){
        if(event.getKeyCode()==KeyEvent.KEYCODE_BACK)return super.dispatchKeyEvent(event);
        if(!active)return super.dispatchKeyEvent(event);
        if(event.getAction()==KeyEvent.ACTION_DOWN)keyDowns++;
        else if(event.getAction()==KeyEvent.ACTION_UP)keyUps++;
        String action=event.getAction()==KeyEvent.ACTION_DOWN?"DOWN":event.getAction()==KeyEvent.ACTION_UP?"UP":"MULTIPLE";
        record(String.format(Locale.ROOT,"KEY %s code=%d(%s) scan=%d repeat=%d dev=%d src=0x%08x meta=0x%x",
                action,event.getKeyCode(),KeyEvent.keyCodeToString(event.getKeyCode()),event.getScanCode(),event.getRepeatCount(),event.getDeviceId(),event.getSource(),event.getMetaState()));
        return true;
    }

    @Override public boolean dispatchGenericMotionEvent(MotionEvent event){
        if(active&&event.isFromSource(InputDevice.SOURCE_JOYSTICK)){
            recordJoystick(event);return true;
        }
        if(active&&isMouse(event)){
            recordMouse(event);return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event){
        // Local touchscreen gestures can still scroll the diagnostic UI and are not recorded.
        if(active&&isMouse(event))recordMouse(event);
        return super.dispatchTouchEvent(event);
    }
    private static boolean isMouse(MotionEvent event){
        return event.isFromSource(InputDevice.SOURCE_MOUSE)||event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE);
    }

    private void recordMouse(MotionEvent event){
        mouseEvents++;
        long now=SystemClock.uptimeMillis();int action=event.getActionMasked(),buttons=event.getButtonState();
        boolean edge=action!=lastMouseAction||buttons!=lastMouseButtons||event.getDeviceId()!=lastMouseDevice;
        boolean wheel=event.getAxisValue(MotionEvent.AXIS_VSCROLL)!=0||event.getAxisValue(MotionEvent.AXIS_HSCROLL)!=0;
        if(edge||wheel||now-lastMouseAt>=50){
            lastMouseAt=now;
            record(String.format(Locale.ROOT,"MOUSE %s dev=%d src=0x%08x x=%.1f y=%.1f buttons=0x%x actionButton=0x%x wheel=%.2f hWheel=%.2f relX=%.1f relY=%.1f",
                    MotionEvent.actionToString(event.getAction()),event.getDeviceId(),event.getSource(),event.getX(),event.getY(),buttons,event.getActionButton(),
                    event.getAxisValue(MotionEvent.AXIS_VSCROLL),event.getAxisValue(MotionEvent.AXIS_HSCROLL),event.getAxisValue(MotionEvent.AXIS_RELATIVE_X),event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)));
        }else scheduleUi();
        lastMouseAction=action;lastMouseButtons=buttons;lastMouseDevice=event.getDeviceId();
    }
    private void recordJoystick(MotionEvent event){
        joystickEvents++;
        float[] values=new float[AXES.length];boolean neutral=true;
        StringBuilder details=new StringBuilder();
        for(int i=0;i<AXES.length;i++){
            values[i]=event.getAxisValue(AXES[i]);if(Math.abs(values[i])>.001f)neutral=false;
            details.append(String.format(Locale.ROOT," %s=%.3f",AXIS_NAMES[i],values[i]));
        }
        if(haveAxes&&lastAxisDevice==event.getDeviceId()&&Arrays.equals(values,lastAxes)){scheduleUi();return;}
        boolean edge=!haveAxes||lastAxisDevice!=event.getDeviceId()||neutral!=lastAxesNeutral;
        System.arraycopy(values,0,lastAxes,0,values.length);haveAxes=true;lastAxesNeutral=neutral;lastAxisDevice=event.getDeviceId();
        String line=String.format(Locale.ROOT,"PAD dev=%d src=0x%08x%s",event.getDeviceId(),event.getSource(),details);
        long now=SystemClock.uptimeMillis();
        if(edge||now-lastAxesAt>=50){
            handler.removeCallbacks(flushPendingAxes);pendingAxes=null;lastAxesAt=now;record(line);
        }else{
            pendingAxes=line;handler.removeCallbacks(flushPendingAxes);handler.postDelayed(flushPendingAxes,50-(now-lastAxesAt));scheduleUi();
        }
    }
    private final Runnable flushPendingAxes=new Runnable(){
        @Override public void run(){
            if(pendingAxes!=null){String line=pendingAxes;pendingAxes=null;lastAxesAt=SystemClock.uptimeMillis();record(line);}
        }
    };

    private final InputManager.InputDeviceListener deviceListener=new InputManager.InputDeviceListener(){
        @Override public void onInputDeviceAdded(int id){deviceChanged("added",id);}
        @Override public void onInputDeviceRemoved(int id){deviceChanged("removed",id);}
        @Override public void onInputDeviceChanged(int id){deviceChanged("changed",id);}
    };
    private void deviceChanged(String what,int id){
        if(!active)return;refreshDevices();record("DEVICE "+what+" id="+id);
    }
    private void refreshDevices(){
        StringBuilder all=new StringBuilder("AirDeck receiver input devices\nCaptured uptime=").append(SystemClock.uptimeMillis()).append("\n");
        deviceCount=0;
        for(int id:InputDevice.getDeviceIds()){
            InputDevice d=InputDevice.getDevice(id);if(d==null)continue;deviceCount++;
            all.append(String.format(Locale.ROOT,"\nid=%d name=%s\nvendor=%d product=%d source=0x%08x keyboardType=%d virtual=%s\ndescriptor=%s\n",
                    d.getId(),d.getName(),d.getVendorId(),d.getProductId(),d.getSources(),d.getKeyboardType(),d.isVirtual(),d.getDescriptor()));
            List<InputDevice.MotionRange> ranges=d.getMotionRanges();
            for(InputDevice.MotionRange range:ranges){
                all.append(String.format(Locale.ROOT,"  %s source=0x%08x min=%.3f max=%.3f flat=%.3f fuzz=%.3f resolution=%.3f\n",
                        MotionEvent.axisToString(range.getAxis()),range.getSource(),range.getMin(),range.getMax(),range.getFlat(),range.getFuzz(),range.getResolution()));
            }
        }
        devices.setText(all.toString());
        try(BufferedWriter writer=new BufferedWriter(new OutputStreamWriter(new FileOutputStream(new File(getFilesDir(),"probe-devices.txt")),StandardCharsets.UTF_8))){writer.write(all.toString());}
        catch(IOException error){diskError="设备日志写入失败";Log.w(TAG,"Device snapshot write failed",error);}
        scheduleUi();
    }

    private void clearProbe(){
        handler.removeCallbacks(flushPendingAxes);pendingAxes=null;
        keyDowns=keyUps=mouseEvents=joystickEvents=0;
        lastMouseAction=-1;lastMouseButtons=-1;lastMouseDevice=Integer.MIN_VALUE;
        haveAxes=false;lastAxesNeutral=true;lastAxisDevice=Integer.MIN_VALUE;
        history.clear();diskError="";closeLog();openLog(false);
        record("INFO counters cleared; only foreground-window events are recorded");
        refreshDevices();
    }
    private void record(String detail){
        String line=SystemClock.uptimeMillis()+" "+detail;
        history.addLast(line);while(history.size()>200)history.removeFirst();
        Log.i(TAG,detail);
        if(logWriter!=null){
            try{
                if(logBytes>MAX_LOG_BYTES){
                    closeLog();openLog(false);
                    if(logWriter!=null)for(String retained:history){logWriter.write(retained);logWriter.newLine();logBytes+=retained.getBytes(StandardCharsets.UTF_8).length+1;}
                }else{
                    logWriter.write(line);logWriter.newLine();logBytes+=line.getBytes(StandardCharsets.UTF_8).length+1;
                }
                if(logWriter!=null)logWriter.flush();
            }catch(IOException error){diskError="事件日志写入失败";Log.w(TAG,"Event log write failed",error);closeLog();}
        }
        scheduleUi();
    }
    private void openLog(boolean append){
        closeLog();
        try{
            logWriter=new BufferedWriter(new OutputStreamWriter(new FileOutputStream(logFile,append),StandardCharsets.UTF_8));
            logBytes=append?logFile.length():0;
        }catch(IOException error){diskError="无法打开事件日志";Log.w(TAG,"Event log unavailable",error);}
    }
    private void closeLog(){
        if(logWriter==null)return;
        try{logWriter.close();}catch(IOException ignored){}logWriter=null;
    }
    private void scheduleUi(){if(!uiPending){uiPending=true;handler.postDelayed(renderUi,60);}}
    private final Runnable renderUi=new Runnable(){
        @Override public void run(){
            uiPending=false;
            counters.setText(String.format(Locale.ROOT,"按下 %d  /  松开 %d\n鼠标事件 %d  /  手柄事件 %d  /  设备 %d%s",
                    keyDowns,keyUps,mouseEvents,joystickEvents,deviceCount,diskError.isEmpty()?"":"\n"+diskError));
            StringBuilder recent=new StringBuilder();int skip=Math.max(0,history.size()-12),index=0;
            for(String line:history)if(index++>=skip)recent.append(line).append('\n');
            events.setText(recent.length()==0?"等待实际输入设备事件":recent.toString());
        }
    };
    private TextView text(String value,float size,boolean mono){
        TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(0xFF192D29);v.setIncludeFontPadding(false);
        if(mono)v.setTypeface(Typeface.MONOSPACE);return v;
    }
    private GradientDrawable round(int color){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(12));return d;}
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
