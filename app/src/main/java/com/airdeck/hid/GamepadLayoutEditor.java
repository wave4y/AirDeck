package com.airdeck.hid;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** A fixed-height toolbar keeps the controller canvas stable while editing. */
public final class GamepadLayoutEditor extends LinearLayout {
    private static final int BACKGROUND=0xFF1A2421, SURFACE=0xFF28382F;
    private static final int BORDER=0xFF4D6555, TEXT=0xFFDEE9E3, MUTED=0xFF98AEA0;
    private static final int MINT=0xFFB6E6B9;
    private final GamepadView gamepad;
    private final TextView adjust,selection,smaller,percent,larger;
    private final LinearLayout editingBar;
    private AlertDialog selectionDialog;

    public GamepadLayoutEditor(Context context,GamepadView gamepad){
        super(context);
        if(gamepad==null)throw new IllegalArgumentException("gamepad");
        this.gamepad=gamepad;
        setOrientation(VERTICAL);setBackgroundColor(BACKGROUND);
        setMotionEventSplittingEnabled(true);

        FrameLayout toolbar=new FrameLayout(context);
        addView(toolbar,new LayoutParams(LayoutParams.MATCH_PARENT,dp(44)));
        adjust=button("调整布局","调整手柄布局",false);
        FrameLayout.LayoutParams normal=new FrameLayout.LayoutParams(dp(108),dp(34),Gravity.CENTER);
        toolbar.addView(adjust,normal);
        adjust.setOnClickListener(v->{
            if(!gamepad.beginLayoutEditing())Toast.makeText(getContext(),"请在横屏下调整布局",Toast.LENGTH_SHORT).show();
            refresh();
        });

        editingBar=new LinearLayout(context);
        editingBar.setOrientation(HORIZONTAL);editingBar.setGravity(Gravity.CENTER_VERTICAL);
        editingBar.setPadding(dp(4),0,dp(4),0);
        toolbar.addView(editingBar,new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.MATCH_PARENT));

        selection=button("点选或拖动控件 ▾","选择调整控件",false);
        selection.setGravity(Gravity.CENTER_VERTICAL|Gravity.LEFT);
        selection.setPadding(dp(10),0,dp(7),0);
        editingBar.addView(selection,new LayoutParams(0,dp(36),1));
        selection.setOnClickListener(v->showControlChoices());

        smaller=button("减小","减小选中控件，每次百分之五",false);
        addTool(smaller,42,8);
        smaller.setOnClickListener(v->{gamepad.resizeSelectedControl(-.05f);refresh();});
        percent=label("—");percent.setTextColor(MUTED);percent.setGravity(Gravity.CENTER);
        addTool(percent,44,0);
        larger=button("放大","放大选中控件，每次百分之五",false);
        addTool(larger,42,0);
        larger.setOnClickListener(v->{gamepad.resizeSelectedControl(.05f);refresh();});

        TextView reset=button("恢复","恢复默认手柄布局，保存前可以取消",false);
        addTool(reset,46,8);
        reset.setOnClickListener(v->{gamepad.resetEditedLayout();refresh();});
        TextView cancel=button("取消","取消布局调整",false);
        addTool(cancel,46,5);
        cancel.setOnClickListener(v->cancelEditing());
        TextView save=button("保存","保存手柄布局",true);
        addTool(save,46,5);
        save.setOnClickListener(v->{dismissControlChoices();gamepad.saveLayoutEditing();refresh();});

        addView(gamepad,new LayoutParams(LayoutParams.MATCH_PARENT,0,1));
        gamepad.setEditListener(this::refresh);
        refresh();
    }

    /** Consume Back while there is an unsaved layout draft. */
    public boolean handleBack(){
        if(!gamepad.isLayoutEditing())return false;
        cancelEditing();return true;
    }

    private void cancelEditing(){
        dismissControlChoices();gamepad.cancelLayoutEditing();refresh();
    }

    private void refresh(){
        boolean editing=gamepad.isLayoutEditing();
        adjust.setVisibility(editing?GONE:VISIBLE);
        editingBar.setVisibility(editing?VISIBLE:GONE);
        String label=gamepad.getSelectedControlLabel();
        int size=gamepad.getSelectedControlSizePercent();
        boolean selected=editing&&label!=null&&!label.isEmpty()&&size>0;
        selection.setText(selected?label+" ▾":"点选或拖动控件 ▾");
        selection.setContentDescription(selected?"选择调整控件，当前为"+label:"选择调整控件，点选或拖动控件");
        percent.setText(selected?size+"%":"—");
        percent.setContentDescription(selected?"所选控件大小，百分之"+size:"尚未选择控件");
        setToolEnabled(smaller,selected&&size>65);
        setToolEnabled(larger,selected&&size<150);
        if(!editing)dismissControlChoices();
    }

    private void showControlChoices(){
        if(!gamepad.isLayoutEditing())return;
        final String[] labels=gamepad.getEditableControlLabels();
        final int[] ids=gamepad.getEditableControlIds();
        if(labels==null||ids==null||labels.length==0||labels.length!=ids.length)return;
        dismissControlChoices();
        String selected=gamepad.getSelectedControlLabel();int checked=-1;
        for(int i=0;i<labels.length;i++)if(labels[i].equals(selected)){checked=i;break;}
        selectionDialog=new AlertDialog.Builder(getContext())
                .setTitle("选择要调整的控件")
                .setSingleChoiceItems(labels,checked,(dialog,index)->{
                    if(gamepad.isLayoutEditing())gamepad.selectEditableControl(ids[index]);
                    dialog.dismiss();refresh();
                })
                .setNegativeButton("取消",null).create();
        selectionDialog.setOnDismissListener(dialog->selectionDialog=null);
        selectionDialog.show();
    }

    private void dismissControlChoices(){
        if(selectionDialog!=null){AlertDialog dialog=selectionDialog;selectionDialog=null;dialog.dismiss();}
    }

    private void setToolEnabled(TextView view,boolean enabled){view.setEnabled(enabled);view.setAlpha(enabled?1f:.32f);}

    private void addTool(TextView view,int width,int leftMargin){
        LayoutParams lp=new LayoutParams(dp(width),dp(36));lp.leftMargin=dp(leftMargin);editingBar.addView(view,lp);
    }

    private TextView label(String text){
        TextView view=new TextView(getContext());view.setText(text);view.setTextColor(TEXT);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP,12);view.setTypeface(Typeface.create("sans-serif-medium",0));
        view.setSingleLine(true);view.setEllipsize(TextUtils.TruncateAt.END);view.setIncludeFontPadding(false);
        view.setGravity(Gravity.CENTER);view.setMinWidth(0);view.setMinHeight(0);
        view.setAutoSizeTextTypeUniformWithConfiguration(9,12,1,TypedValue.COMPLEX_UNIT_SP);
        return view;
    }

    private TextView button(String text,String description,boolean primary){
        TextView view=label(text);view.setContentDescription(description);
        view.setClickable(true);view.setFocusable(true);view.setPadding(dp(3),0,dp(3),0);
        GradientDrawable face=new GradientDrawable();face.setCornerRadius(dp(11));
        face.setColor(primary?MINT:SURFACE);face.setStroke(dp(1),primary?MINT:BORDER);
        GradientDrawable mask=new GradientDrawable();mask.setColor(0xFFFFFFFF);mask.setCornerRadius(dp(11));
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x335F936E),face,mask));
        if(primary)view.setTextColor(BACKGROUND);
        return view;
    }

    @Override protected void onDetachedFromWindow(){dismissControlChoices();super.onDetachedFromWindow();}
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
