package com.airdeck.hid;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;

/** Native controls backed by the platform Bluetooth HID Device profile. */
public final class MainActivity extends Activity implements HidController.Listener {
    static final int BG=0xFFF4F6F3, INK=0xFF192D29, MUTED=0xFF74827B,
            GREEN=0xFF426D58, MINT=0xFFDCECD9, LINE=0xFFE0E6DF, WHITE=0xFFFFFFFF;
    private HidController hid;
    private SharedPreferences prefs;
    private LinearLayout root, body;
    private TextView statusTitle, statusDetail, headerStatus;
    private TouchpadView pad;
    private GamepadView gamepad;
    private GamepadLayoutEditor gamepadEditor;
    private GamepadPreset gamepadPreset=GamepadPreset.XBOX;
    private KeyboardPreset keyboardPreset=KeyboardPreset.STANDARD,comboPreset=KeyboardPreset.STANDARD;
    private CombinedControlsView combined;
    private int tab=0, padMouseButtons=0, physicalMouseButtons=0;
    private float sensitivity=1.35f;
    private boolean haptics=true, resumed=false;
    private long lastHint;
    private String shownProtocolWarning;
    private android.window.OnBackInvokedCallback backCallback;
    private boolean backCallbackRegistered;
    private HidController.State lastHidState;
    private final BroadcastReceiver receiver=new BroadcastReceiver(){
        @Override public void onReceive(Context c,Intent i){
            if(BluetoothAdapter.ACTION_STATE_CHANGED.equals(i.getAction())){
                if(resumed) hid.start();
            }
            if(tab==3 && resumed) render();
        }
    };

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        prefs=getSharedPreferences("controls",MODE_PRIVATE);
        sensitivity=prefs.getFloat("sensitivity",1.35f);
        haptics=prefs.getBoolean("haptics",true);
        try{gamepadPreset=GamepadPreset.valueOf(prefs.getString("gamepad_preset","XBOX"));}
        catch(IllegalArgumentException ignored){gamepadPreset=GamepadPreset.XBOX;}
        keyboardPreset=readKeyboardPreset("keyboard_preset");
        comboPreset=readKeyboardPreset("combo_preset");
        tab=state==null?0:state.getInt("tab",0);

        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        hid=new HidController(this,this);
        IntentFilter filter=new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        if(Build.VERSION.SDK_INT>=33) registerReceiver(receiver,filter,Context.RECEIVER_EXPORTED);
        else registerReceiver(receiver,filter);
        render();
    }
    @Override public void onResume(){super.onResume();resumed=true;hid.setForeground(true);hid.start();updateStatus();}
    @Override public void onPause(){releaseControls();resumed=false;hid.setForeground(false);super.onPause();}
    @Override public void onDestroy(){
        if(Build.VERSION.SDK_INT>=33&&backCallbackRegistered)getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        unregisterReceiver(receiver);hid.close();super.onDestroy();
    }
    @Override public void onSaveInstanceState(Bundle out){out.putInt("tab",tab);super.onSaveInstanceState(out);}
    @Override public void onConfigurationChanged(Configuration c){super.onConfigurationChanged(c);releaseControls();render();}
    @Override public void onBackPressed(){
        if(gamepadEditor!=null&&gamepadEditor.handleBack())return;
        if(controlFullscreen()){releaseControls();setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);return;}
        if(tab!=0){selectTab(0);return;}super.onBackPressed();
    }
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(focused)applyWindowMode();}
    @Override public void onStateChanged(HidController.State state,String detail){runOnUiThread(()->{
        if(lastHidState!=state){lastHidState=state;releaseControls();}
        updateStatus();
        String warning=hid.getProtocolWarning();
        if(warning==null)shownProtocolWarning=null;
        else if(resumed&&!warning.equals(shownProtocolWarning)){
            shownProtocolWarning=warning;
            Toast.makeText(this,warning+"\n可在“设备 → 连接诊断”查看处理方法",Toast.LENGTH_LONG).show();
        }
        if(tab==3&&body!=null)render();
    });}
    @Override public void onKeyboardLedsChanged(int leds){runOnUiThread(()->{if(combined!=null)combined.setKeyboardLeds(leds);});}
    private void releaseControls(){
        if(pad!=null)pad.releaseAll();
        if(gamepad!=null)gamepad.releaseAll();
        if(combined!=null)combined.releaseAll();
        if(hid!=null)hid.releaseAll();
        padMouseButtons=0;physicalMouseButtons=0;
    }
    private void selectTab(int next){
        releaseControls();tab=next;

        render();
    }
    private boolean landscape(){return getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE;}
    private boolean controlFullscreen(){return landscape()&&tab!=3;}
    private void applyWindowMode(){
        boolean full=controlFullscreen();
        if(Build.VERSION.SDK_INT>=33){
            boolean handlesBack=full||tab!=0;
            if(backCallback==null)backCallback=this::onBackPressed;
            if(handlesBack&&!backCallbackRegistered)getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,backCallback);
            else if(!handlesBack&&backCallbackRegistered)getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
            backCallbackRegistered=handlesBack;
        }
        WindowManager.LayoutParams params=getWindow().getAttributes();
        params.layoutInDisplayCutoutMode=full?WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES:WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
        getWindow().setAttributes(params);
        if(full)getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(full
                ?View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                :View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        if(Build.VERSION.SDK_INT>=30){
            android.view.WindowInsetsController bars=getWindow().getInsetsController();
            if(bars!=null){
                bars.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                if(full)bars.hide(android.view.WindowInsets.Type.systemBars());
                else bars.show(android.view.WindowInsets.Type.systemBars());
            }
        }
    }
    private void render(){
        pad=null;gamepad=null;gamepadEditor=null;combined=null;statusTitle=null;statusDetail=null;headerStatus=null;
        applyWindowMode();
        if(controlFullscreen()){
            root=column();root.setBackgroundColor(tab==2?0xFF1A2421:BG);
            int edge=dp(10);root.setPadding(edge,edge,edge,edge);
            root.setOnApplyWindowInsetsListener((v,insets)->{
                android.view.DisplayCutout cutout=insets.getDisplayCutout();
                v.setPadding(edge+(cutout==null?0:cutout.getSafeInsetLeft()),edge+(cutout==null?0:cutout.getSafeInsetTop()),edge+(cutout==null?0:cutout.getSafeInsetRight()),edge+(cutout==null?0:cutout.getSafeInsetBottom()));
                return insets;
            });
            setContentView(root);root.requestApplyInsets();body=root;
            if(tab==0)mousePage();else if(tab==1)keyboardPage();else if(tab==2)gamepadPage();else combinedPage();
            return;
        }
        final int side=dp(tab==1||tab==4?12:20);
        root=column();root.setBackgroundColor(BG);root.setPadding(side,dp(8),side,dp(10));
        // Android 15 enforces edge-to-edge for target 35; protect controls from every system edge.
        if(Build.VERSION.SDK_INT>=35)root.setOnApplyWindowInsetsListener((v,insets)->{
            android.graphics.Insets safe=insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout());
            android.graphics.Insets ime=insets.getInsets(android.view.WindowInsets.Type.ime());
            v.setPadding(side+safe.left,dp(8)+safe.top,side+safe.right,dp(10)+Math.max(safe.bottom,ime.bottom));
            return insets;
        });
        setContentView(root);
        root.requestApplyInsets();
        LinearLayout header=row();header.setGravity(Gravity.CENTER_VERTICAL);
        IconView mark=new IconView(this,3,INK);mark.setBackground(round(MINT,14));
        header.addView(mark,new LinearLayout.LayoutParams(dp(landscape()?32:42),dp(landscape()?32:42)));
        LinearLayout wordmark=column();wordmark.setPadding(dp(11),0,0,0);
        TextView brand=text("AirDeck",landscape()?20:23,INK,true);brand.setLetterSpacing(-0.025f);wordmark.addView(brand);
        if(!landscape())wordmark.addView(text("把手机变成你的无线控制台",10,MUTED,false));
        header.addView(wordmark,new LinearLayout.LayoutParams(0,-2,1));
        if(landscape())header.addView(compactNavigation());
        headerStatus=text("●  未连接",11,GREEN,true);headerStatus.setPadding(dp(10),dp(9),dp(10),dp(9));headerStatus.setBackground(ripple(MINT,20));
        headerStatus.setOnClickListener(v->selectTab(3));header.addView(headerStatus);
        root.addView(header,new LinearLayout.LayoutParams(-1,dp(landscape()?36:58)));
        if(!landscape()&&tab!=1&&tab!=4)connectionCard();
        body=column();
        LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,0,1);bp.topMargin=dp(landscape()?6:14);root.addView(body,bp);
        if(tab==0)mousePage();else if(tab==1)keyboardPage();else if(tab==2)gamepadPage();else if(tab==4)combinedPage();else devicePage();
        if(!landscape())navigation();updateStatus();
    }
    private void connectionCard(){
        LinearLayout card=row();card.setPadding(dp(14),dp(12),dp(14),dp(12));card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(ripple(INK,20));card.setOnClickListener(v->selectTab(3));
        IconView icon=new IconView(this,4,0xFFCFE7CC);card.addView(icon,new LinearLayout.LayoutParams(dp(32),dp(32)));
        LinearLayout words=column();words.setPadding(dp(12),0,0,0);
        statusTitle=text("连接你的设备",15,WHITE,true);words.addView(statusTitle);
        statusDetail=text("通过蓝牙，开始无线操控",11,0xFFAEBFB4,false);statusDetail.setPadding(0,dp(4),0,0);words.addView(statusDetail);
        card.addView(words,new LinearLayout.LayoutParams(0,-2,1));card.addView(text("›",28,0xFFDCECD9,false));
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.topMargin=dp(12);root.addView(card,cp);
    }
    private void updateStatus(){
        if(hid==null||headerStatus==null)return;
        boolean connected=hid.isConnected();
        String warning=hid.getProtocolWarning();
        headerStatus.setText(warning!=null?"!  协议提示":connected?"●  已连接":hid.getState()==HidController.State.CONNECTING?"○  连接中":"○  未连接");
        headerStatus.setTextColor(warning!=null?0xFF99611D:GREEN);
        if(statusTitle!=null){
            BluetoothDevice d=hid.getConnectedDevice();
            statusTitle.setText(connected?deviceName(d):"连接你的设备");
            statusDetail.setText(warning!=null?warning:hid.getStatusText());
        }
    }
    private void pageTitle(String title,String sub,String action,Runnable run){
        if(controlFullscreen())return;
        LinearLayout r=row();r.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout words=column();words.addView(text(title,landscape()?17:23,INK,true));
        if(sub!=null&&!landscape()){TextView t=text(sub,11,MUTED,false);t.setPadding(0,dp(3),0,0);words.addView(t);}
        r.addView(words,new LinearLayout.LayoutParams(0,-2,1));
        if(action!=null){TextView a=button(action,false);a.setTextSize(11);a.setOnClickListener(v->run.run());r.addView(a);}
        if(tab!=2&&tab!=3){TextView rotate=button(landscape()?"竖屏":"横屏",true);rotate.setTextSize(11);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);lp.leftMargin=dp(5);r.addView(rotate,lp);rotate.setOnClickListener(v->setRequestedOrientation(landscape()?ActivityInfo.SCREEN_ORIENTATION_PORTRAIT:ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE));}
        body.addView(r,new LinearLayout.LayoutParams(-1,-2));space(body,landscape()?6:12);
    }
    private void mousePage(){
        pageTitle("触控板","指尖轻移，掌控大屏","使用指南",this::showGuide);
        pad=new TouchpadView(this);pad.setSensitivity(sensitivity);pad.setHapticFeedback(haptics);
        pad.setListener(new TouchpadView.Listener(){
            public void mouseMove(int x,int y){hid.mouseMove(x,y);}
            public void mouseButton(int b,boolean down){if(down)hintIfDisconnected();setMouseSource(true,b,down);}
            public void mouseScroll(int d){hid.mouseScroll(d);}
            public void mouseClick(int b){hintIfDisconnected();hid.mouseClick(b);}
        });
        if(landscape()){mouseLandscape();return;}
        body.addView(pad,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout clicks=row();space(body,10);
        TextView left=button("左键",false),right=button("右键",false);
        holdMouse(left,1);holdMouse(right,2);weighted(clicks,left,1,54,0);weighted(clicks,right,1,54,10);body.addView(clicks);
        space(body,10);
        LinearLayout speed=row();speed.setGravity(Gravity.CENTER_VERTICAL);speed.addView(text("指针速度",11,MUTED,false));
        SeekBar seek=new SeekBar(this);seek.setMax(25);seek.setProgress(Math.round((sensitivity-.5f)*10));
        seek.setProgressTintList(ColorStateList.valueOf(GREEN));seek.setThumbTintList(ColorStateList.valueOf(GREEN));
        speed.addView(seek,new LinearLayout.LayoutParams(0,dp(38),1));
        TextView value=text(String.format(java.util.Locale.ROOT,"%.1f×",sensitivity),11,GREEN,true);speed.addView(value);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar s,int p,boolean user){sensitivity=.5f+p/10f;pad.setSensitivity(sensitivity);value.setText(String.format(java.util.Locale.ROOT,"%.1f×",sensitivity));}
            public void onStartTrackingTouch(SeekBar s){}
            public void onStopTrackingTouch(SeekBar s){prefs.edit().putFloat("sensitivity",sensitivity).apply();}
        });body.addView(speed);
    }
    private void mouseLandscape(){
        LinearLayout area=row();pad.setCompact(true);area.addView(pad,new LinearLayout.LayoutParams(0,-1,1));
        LinearLayout side=column();side.setPadding(dp(14),0,0,0);area.addView(side,new LinearLayout.LayoutParams(dp(156),-1));
        side.addView(text("鼠标按键",12,MUTED,true));space(side,8);LinearLayout buttons=row();TextView left=button("左键",false),right=button("右键",false);holdMouse(left,1);holdMouse(right,2);weighted(buttons,left,1,52,0);weighted(buttons,right,1,52,6);side.addView(buttons);space(side,12);
        TextView value=text(String.format(java.util.Locale.ROOT,"指针速度  %.1f×",sensitivity),11,MUTED,false);side.addView(value);
        SeekBar seek=new SeekBar(this);seek.setMax(25);seek.setProgress(Math.round((sensitivity-.5f)*10));seek.setProgressTintList(ColorStateList.valueOf(GREEN));seek.setThumbTintList(ColorStateList.valueOf(GREEN));side.addView(seek,new LinearLayout.LayoutParams(-1,dp(42)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar s,int p,boolean user){sensitivity=.5f+p/10f;pad.setSensitivity(sensitivity);value.setText(String.format(java.util.Locale.ROOT,"指针速度  %.1f×",sensitivity));}public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){prefs.edit().putFloat("sensitivity",sensitivity).apply();}});
        TextView help=text("双指滑动滚动页面\n按住左键可拖动",11,MUTED,false);help.setLineSpacing(dp(3),1);side.addView(help);body.addView(area,new LinearLayout.LayoutParams(-1,0,1));
    }
    private void setMouseSource(boolean fromPad,int mask,boolean down){
        int before=padMouseButtons|physicalMouseButtons;
        if(fromPad)padMouseButtons=down?(padMouseButtons|mask):(padMouseButtons&~mask);
        else physicalMouseButtons=down?(physicalMouseButtons|mask):(physicalMouseButtons&~mask);
        int after=padMouseButtons|physicalMouseButtons;
        if((before&mask)!=(after&mask))hid.mouseButton(mask,(after&mask)!=0);
    }
    private void holdMouse(TextView key,int mask){
        key.setContentDescription(mask==1?"鼠标左键，支持按住拖动":"鼠标右键");
        key.setOnTouchListener((v,e)->{
            if(e.getActionMasked()==MotionEvent.ACTION_DOWN){hintIfDisconnected();feedback(v);v.setPressed(true);setMouseSource(false,mask,true);return true;}
            if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL){v.setPressed(false);setMouseSource(false,mask,false);if(e.getActionMasked()==MotionEvent.ACTION_UP)v.performClick();return true;}return true;
        });
    }
    private void keyboardPage(){
        keyboardHeading(false);
        combined=new CombinedControlsView(this,hid,true,keyboardPreset);
        combined.setHapticFeedback(haptics);
        body.addView(combined,new LinearLayout.LayoutParams(-1,0,1));
    }
    private KeyboardPreset readKeyboardPreset(String key){
        try{return KeyboardPreset.valueOf(prefs.getString(key,"STANDARD"));}
        catch(IllegalArgumentException ignored){return KeyboardPreset.STANDARD;}
    }
    private void keyboardHeading(boolean mouse){
        if(controlFullscreen())return;
        KeyboardPreset current=mouse?comboPreset:keyboardPreset;
        LinearLayout heading=row();heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(text(mouse?"键鼠同屏":"键盘",22,INK,true),new LinearLayout.LayoutParams(0,-2,1));
        TextView layout=button((current==KeyboardPreset.SIMPLE?"简洁":"标准")+" ▾",true);
        layout.setContentDescription("切换键盘布局");layout.setOnClickListener(v->chooseKeyboardPreset(mouse));
        TextView input=button("输入文本",false);input.setOnClickListener(v->textInput());
        TextView rotate=button("横屏",true);rotate.setOnClickListener(v->setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE));
        for(TextView action:new TextView[]{layout,input,rotate}){
            action.setTextSize(11);action.setPadding(dp(9),dp(9),dp(9),dp(9));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,dp(38));lp.leftMargin=dp(5);heading.addView(action,lp);
        }
        body.addView(heading);space(body,12);
    }
    private void chooseKeyboardPreset(boolean mouse){
        KeyboardPreset current=mouse?comboPreset:keyboardPreset;
        String[] labels={mouse?"标准 · 编辑、方向键独立分区":"标准 · 完整 87 键","简洁 · 常用大键"};
        new AlertDialog.Builder(this).setTitle(mouse?"键鼠布局":"键盘布局")
                .setSingleChoiceItems(labels,current==KeyboardPreset.SIMPLE?1:0,(dialog,index)->{
                    releaseControls();KeyboardPreset selected=index==1?KeyboardPreset.SIMPLE:KeyboardPreset.STANDARD;
                    if(mouse)comboPreset=selected;else keyboardPreset=selected;
                    prefs.edit().putString(mouse?"combo_preset":"keyboard_preset",selected.name()).apply();
                    dialog.dismiss();render();
                }).setNegativeButton("取消",null).show();
    }
    private void textInput(){
        EditText input=new EditText(this);input.setTextColor(INK);input.setTextSize(16);input.setHint("英文、数字或拼音，例如 hello");input.setMinLines(3);input.setGravity(Gravity.TOP);input.setPadding(dp(20),dp(12),dp(20),dp(12));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("发送文本").setMessage("接收端无需安装应用。支持英文、数字、标点和换行。\n\n输入中文：先在接收端切换到拼音输入法，在这里输入拼音并发送，然后在接收端选字；不能直接发送整段汉字。").setView(input).setNegativeButton("取消",null).setPositiveButton("发送",null).create();
        dialog.setOnShowListener(x->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String s=input.getText().toString();if(s.isEmpty())return;
            if(!hid.isConnected()){toast("请先连接另一台手机");return;}
            for(int i=0;i<s.length();i++){char c=s.charAt(i);if((c<32&&c!='\n'&&c!='\t'&&c!='\r')||c>126){input.setError("请使用英文、数字或拼音；此处不直接发送中文");return;}}
            releaseControls();hid.typeText(s);dialog.dismiss();toast("正在发送文本");
        }));dialog.show();
    }
    private void gamepadPage(){
        if(!controlFullscreen()){
            pageTitle("游戏手柄","选择熟悉的布局，横屏开始游戏","横屏",()->setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE));
            LinearLayout selector=row();selector.setGravity(Gravity.CENTER_VERTICAL);selector.setPadding(dp(14),dp(10),dp(14),dp(10));
            selector.setBackground(ripple(MINT,14));selector.setContentDescription("切换手柄布局");selector.setClickable(true);selector.setFocusable(true);
            selector.addView(text(gamepadPreset.label,17,INK,true),new LinearLayout.LayoutParams(0,-2,1));
            selector.addView(text("布局  ▾",12,GREEN,true));selector.setOnClickListener(v->chooseGamepadPreset());
            body.addView(selector);space(body,8);
            TextView description=text(gamepadPreset.description,11,MUTED,false);body.addView(description);space(body,10);
        }
        gamepad=new GamepadView(this);gamepad.setHapticFeedback(haptics);
        gamepad.setPreset(gamepadPreset);gamepad.setImmersive(controlFullscreen());
        gamepad.setListener((buttons,hat,lx,ly,rx,ry,lt,rt)->hid.gamepadState(buttons,hat,lx,ly,rx,ry,lt,rt));
        if(controlFullscreen()){
            gamepadEditor=new GamepadLayoutEditor(this,gamepad);
            body.addView(gamepadEditor,new LinearLayout.LayoutParams(-1,0,1));
        }else body.addView(gamepad,new LinearLayout.LayoutParams(-1,0,1));
        if(!controlFullscreen()){
            TextView note=text("横屏可调整按键 · 系统返回可回到竖屏",10,MUTED,false);note.setGravity(Gravity.CENTER);note.setPadding(0,dp(8),0,dp(4));body.addView(note);
        }
    }
    private void chooseGamepadPreset(){
        LinearLayout choices=column();choices.setPadding(dp(20),dp(8),dp(20),dp(8));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("手柄布局").setView(choices).setNegativeButton("取消",null).create();
        GamepadPreset[] presets=GamepadPreset.values();
        for(int start=0;start<presets.length;start+=3){
            LinearLayout line=row();
            for(int column=0;column<3;column++){
                int index=start+column;
                if(index>=presets.length){weighted(line,new View(this),1,48,column==0?0:7);continue;}
                GamepadPreset preset=presets[index];TextView choice=button(preset.label,preset==gamepadPreset);choice.setTextSize(13);
                choice.setContentDescription(preset.label+" 手柄布局"+(preset==gamepadPreset?"，已选择":""));choice.setSelected(preset==gamepadPreset);
                choice.setOnClickListener(v->{releaseControls();gamepadPreset=preset;prefs.edit().putString("gamepad_preset",preset.name()).apply();dialog.dismiss();render();});
                weighted(line,choice,1,48,column==0?0:7);
            }
            choices.addView(line);if(start+3<presets.length)space(choices,8);
        }
        dialog.show();
    }
    private void combinedPage(){
        keyboardHeading(true);
        combined=new CombinedControlsView(this,hid,false,comboPreset);combined.setSensitivity(sensitivity);combined.setHapticFeedback(haptics);
        body.addView(combined,new LinearLayout.LayoutParams(-1,0,1));
    }
    private LinearLayout compactNavigation(){
        LinearLayout nav=row();String[] names={"触控板","键盘","键鼠","手柄","设备"};int[] tabs={0,1,4,2,3};
        for(int i=0;i<tabs.length;i++){final int n=tabs[i];TextView b=button(names[i],tab==n);b.setTextSize(10);b.setPadding(dp(10),dp(5),dp(10),dp(5));b.setOnClickListener(v->selectTab(n));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(30));p.rightMargin=dp(5);nav.addView(b,p);}return nav;
    }
    private void devicePage(){
        pageTitle("我的设备","一次配对，随时接管","刷新",()->{hid.start();render();});
        ScrollView scroll=new ScrollView(this);scroll.setVerticalScrollBarEnabled(false);LinearLayout content=column();scroll.addView(content);body.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout connection=column();connection.setPadding(dp(14),dp(12),dp(14),dp(12));connection.setBackground(ripple(WHITE,14));
        connection.addView(text("自动连接上次设备",13,INK,true));
        TextView reconnect=text(hid.getAutoReconnectStatus(),11,MUTED,false);reconnect.setPadding(0,dp(6),0,0);connection.addView(reconnect);
        String protocolWarning=hid.getProtocolWarning();
        if(protocolWarning!=null){TextView warning=text("!  "+protocolWarning+"\n点击查看处理方法",11,0xFF99611D,false);warning.setPadding(0,dp(8),0,0);connection.addView(warning);}
        connection.setOnClickListener(v->showDiagnostics());content.addView(connection);space(content,12);
        LinearLayout intro=column();intro.setPadding(dp(17),dp(16),dp(17),dp(16));intro.setBackground(round(MINT,20));
        intro.addView(text("让另一台手机发现你",17,INK,true));
        TextView guide=text("开启可发现后，在另一台安卓手机的蓝牙设置里添加本手机，并确认配对码。已配对设备会显示在下方。",12,GREEN,false);guide.setLineSpacing(dp(3),1);guide.setPadding(0,dp(7),0,dp(12));intro.addView(guide);
        TextView discover=button("＋  配对新设备",true);discover.setBackground(ripple(INK,12));discover.setTextColor(WHITE);discover.setOnClickListener(v->discoverable());intro.addView(discover,new LinearLayout.LayoutParams(-1,dp(45)));content.addView(intro);space(content,20);
        content.addView(text("已配对设备",12,MUTED,true));space(content,10);
        if(!hasPermissions()){
            TextView allow=button("允许附近设备权限",true);allow.setOnClickListener(v->requestBluetooth());content.addView(allow,new LinearLayout.LayoutParams(-1,dp(48)));
        }else{
            List<BluetoothDevice> devices=hid.getPairedDevices();
            if(devices.isEmpty()){
                TextView empty=text("还没有可用的配对设备\n先开启蓝牙，并在接收端完成配对",12,MUTED,false);empty.setGravity(Gravity.CENTER);empty.setPadding(dp(14),dp(24),dp(14),dp(24));empty.setBackground(round(WHITE,16));content.addView(empty);
            }else for(BluetoothDevice d:devices){
                LinearLayout item=row();item.setGravity(Gravity.CENTER_VERTICAL);item.setPadding(dp(14),dp(13),dp(14),dp(13));item.setBackground(ripple(WHITE,14));
                IconView icon=new IconView(this,5,GREEN);item.addView(icon,new LinearLayout.LayoutParams(dp(30),dp(30)));
                LinearLayout words=column();words.setPadding(dp(12),0,dp(4),0);words.addView(text(deviceName(d),14,INK,true));
                boolean connected=d.equals(hid.getConnectedDevice());words.addView(text(connected?"已连接 · 点击断开":"已配对 · 点击连接",10,MUTED,false));item.addView(words,new LinearLayout.LayoutParams(0,-2,1));item.addView(text(connected?"●":"↗",17,GREEN,true));
                item.setOnClickListener(v->{if(d.equals(hid.getConnectedDevice())){hid.disconnect();}else{requestConnect(d);}item.postDelayed(()->{if(tab==3&&resumed)render();},800);});
                LinearLayout.LayoutParams ilp=new LinearLayout.LayoutParams(-1,-2);ilp.bottomMargin=dp(8);content.addView(item,ilp);
            }
        }
        space(content,16);
        LinearLayout settings=column();settings.setPadding(dp(14),dp(7),dp(14),dp(7));settings.setBackground(round(WHITE,16));
        LinearLayout hapticRow=row();hapticRow.setGravity(Gravity.CENTER_VERTICAL);hapticRow.addView(text("触感反馈",13,INK,false),new LinearLayout.LayoutParams(0,dp(44),1));Switch toggle=new Switch(this);toggle.setContentDescription("触感反馈");toggle.setChecked(haptics);toggle.setButtonTintList(ColorStateList.valueOf(GREEN));toggle.setOnCheckedChangeListener((v,checked)->{haptics=checked;prefs.edit().putBoolean("haptics",checked).apply();});hapticRow.addView(toggle);settings.addView(hapticRow);
        settings.addView(divider());TextView bt=text("系统蓝牙设置                                      ›",13,INK,false);bt.setGravity(Gravity.CENTER_VERTICAL);bt.setOnClickListener(v->startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));settings.addView(bt,new LinearLayout.LayoutParams(-1,dp(48)));settings.addView(divider());TextView diagnose=text("连接诊断                                              ›",13,INK,false);diagnose.setGravity(Gravity.CENTER_VERTICAL);diagnose.setOnClickListener(v->showDiagnostics());settings.addView(diagnose,new LinearLayout.LayoutParams(-1,dp(48)));content.addView(settings);
        space(content,14);TextView help=text("请保持 AirDeck 在前台，打开应用后会自动连接上次成功连接的设备。若按字母变成鼠标移动，请查看连接诊断，清理两端的旧配对记录。",11,MUTED,false);help.setLineSpacing(dp(3),1);content.addView(help);space(content,16);
    }
    private String deviceName(BluetoothDevice d){if(d==null)return "蓝牙设备";try{String n=d.getName();return n==null?"蓝牙设备":n;}catch(SecurityException e){return "蓝牙设备";}}
    private void requestConnect(BluetoothDevice device){if(!hasPermissions()){requestBluetooth();return;}hid.start();hid.connect(device);updateStatus();}
    private boolean hasPermissions(){return Build.VERSION.SDK_INT<31||(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED&&checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)==PackageManager.PERMISSION_GRANTED);}
    private void requestBluetooth(){
        if(Build.VERSION.SDK_INT>=31&&!hasPermissions()){requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_ADVERTISE},41);return;}
        BluetoothAdapter a=BluetoothAdapter.getDefaultAdapter();if(a!=null&&!a.isEnabled())startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),42);else hid.start();
    }
    private void discoverable(){
        if(!hasPermissions()){requestBluetooth();return;}
        hid.start();
        Intent i=new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);i.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION,120);
        try{startActivityForResult(i,43);}catch(Exception e){toast("请在系统蓝牙设置里开启可发现");startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));}
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);if(request==41){if(hasPermissions()){hid.start();toast("权限已开启，再点击配对新设备");}else toast("连接需要附近设备权限，可在系统设置里允许");render();}}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==42||request==43){hid.start();render();}}
    private void navigation(){
        LinearLayout nav=row();nav.setGravity(Gravity.CENTER_VERTICAL);nav.setPadding(dp(3),dp(4),dp(3),dp(4));nav.setBackground(round(WHITE,20));
        String[] labels={"触控板","键盘","键鼠","手柄","设备"};int[] tabs={0,1,4,2,3};
        for(int i=0;i<5;i++){
            final int next=tabs[i];LinearLayout item=column();item.setGravity(Gravity.CENTER);item.setContentDescription(labels[i]);item.setClickable(true);item.setBackground(ripple(tab==next?MINT:WHITE,15));
            IconView icon=new IconView(this,next==3?5:next==4?1:next,tab==next?GREEN:MUTED);item.addView(icon,new LinearLayout.LayoutParams(dp(24),dp(24)));
            TextView t=text(labels[i],10,tab==next?GREEN:MUTED,tab==next);t.setGravity(Gravity.CENTER);item.addView(t);item.setOnClickListener(v->selectTab(next));weighted(nav,item,1,landscape()?44:56,i==0?0:4);
        }
        LinearLayout.LayoutParams nlp=new LinearLayout.LayoutParams(-1,-2);nlp.topMargin=dp(12);root.addView(nav,nlp);
    }
    private void showDiagnostics(){
        String warning=hid.getProtocolWarning();
        String protocol=warning==null?"尚未发现可检测的协议异常。\n本机无法读取接收端缓存，仍需实际测试输入。":warning;
        new AlertDialog.Builder(this).setTitle("连接诊断").setMessage(hid.getStatusText()+"\n\n"+hid.getAutoReconnectStatus()+"\n\n"+protocol+"\n\n已提交输入报告："+hid.getSentReportCount()+"\n发送失败："+hid.getFailedReportCount()+"\n提交成功只表示本机蓝牙已接受数据。\n\n按字母变成鼠标移动，或连接后没有反应？可能是旧输入协议缓存：\n1. 在两台手机上互相取消配对。\n2. 两台手机都关闭蓝牙，再重新开启。\n3. 关闭其他模拟键鼠 App，保持 AirDeck 在前台。\n4. 重新配对，再测试字母、鼠标与手柄。").setPositiveButton("知道了",null).setNeutralButton("蓝牙设置",(d,w)->startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS))).show();
    }
    private void showGuide(){new AlertDialog.Builder(this).setTitle("指尖的几个小技巧").setMessage("单指滑动：移动鼠标\n轻点：左键单击\n双指轻点：右键单击\n双指上下滑动：滚动页面\n按住左键再滑动：拖动窗口\n\n连接后，另一台安卓手机会将本机识别为蓝牙输入设备，无需安装接收端 App。").setPositiveButton("知道了",null).show();}
    private void hintIfDisconnected(){if(!hid.isConnected()&&System.currentTimeMillis()-lastHint>3500){lastHint=System.currentTimeMillis();toast("当前未连接，可先体验面板；连接请点右上角");}}
    private void feedback(View v){if(haptics)v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
    private int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setMotionEventSplittingEnabled(true);return l;}
    private TextView text(String s,float size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setFontFeatureSettings("kern");t.setIncludeFontPadding(false);t.setTypeface(Typeface.create("sans-serif",bold?Typeface.BOLD:Typeface.NORMAL));return t;}
    private TextView button(String s,boolean accent){TextView t=text(s,14,accent?GREEN:INK,true);t.setGravity(Gravity.CENTER);t.setPadding(dp(12),dp(9),dp(12),dp(9));t.setBackground(ripple(accent?MINT:WHITE,12));t.setClickable(true);t.setFocusable(true);return t;}
    private GradientDrawable round(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private RippleDrawable ripple(int color,int radius){return new RippleDrawable(ColorStateList.valueOf(0x24426D58),round(color,radius),round(WHITE,radius));}
    private void space(LinearLayout l,int size){View v=new View(this);l.addView(v,new LinearLayout.LayoutParams(1,dp(size)));}
    private View divider(){View v=new View(this);v.setBackgroundColor(LINE);v.setLayoutParams(new LinearLayout.LayoutParams(-1,dp(1)));return v;}
    private void weighted(LinearLayout l,View v,float weight,int height,int gap){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(height),weight);p.leftMargin=dp(gap);l.addView(v,p);}

    /** Small consistent stroke icons, independent of font glyph availability. */
    static class IconView extends View{
        private final Paint p=new Paint(3);private final int kind,color;
        IconView(Context c,int kind,int color){super(c);this.kind=kind;this.color=color;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        @Override protected void onDraw(Canvas c){super.onDraw(c);c.save();float size=Math.min(getWidth(),getHeight());c.translate((getWidth()-size)/2,(getHeight()-size)/2);c.scale(size/32,size/32);p.setColor(color);p.setStrokeWidth(1.7f);p.setStyle(Paint.Style.STROKE);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);
            if(kind==0){c.drawRoundRect(7,4,25,28,8,8,p);c.drawLine(16,5,16,12,p);c.drawLine(8,14,24,14,p);}
            else if(kind==1){c.drawRoundRect(3,7,29,25,3,3,p);for(int y=12;y<=17;y+=5)for(int x=8;x<=24;x+=5)c.drawPoint(x,y,p);c.drawLine(10,21,22,21,p);}
            else if(kind==2){Path q=new Path();q.moveTo(9,8);q.lineTo(23,8);q.cubicTo(28,8,31,26,26,26);q.cubicTo(23,26,23,21,20,21);q.lineTo(12,21);q.cubicTo(9,21,9,26,6,26);q.cubicTo(1,26,4,8,9,8);c.drawPath(q,p);c.drawLine(8,14,14,14,p);c.drawLine(11,11,11,17,p);c.drawPoint(22,13,p);c.drawPoint(25,17,p);}
            else if(kind==3){c.drawRoundRect(6,6,26,26,5,5,p);c.drawLine(11,21,16,11,p);c.drawLine(16,11,21,21,p);c.drawLine(13,18,19,18,p);}
            else if(kind==4){Path q=new Path();q.moveTo(11,9);q.lineTo(23,21);q.lineTo(16,27);q.lineTo(16,5);q.lineTo(23,11);q.lineTo(11,23);c.drawPath(q,p);}
            else{c.drawRoundRect(3,5,29,23,2,2,p);c.drawLine(16,23,16,28,p);c.drawLine(10,28,22,28,p);}
            c.restore();
        }
    }
}
