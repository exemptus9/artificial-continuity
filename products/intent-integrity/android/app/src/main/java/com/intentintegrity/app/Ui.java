package com.intentintegrity.app;
import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.WindowInsets;
import android.widget.*;

public final class Ui {
    public static final int BG=Color.rgb(11,17,24), PANEL=Color.rgb(22,32,43), TEXT=Color.rgb(238,246,247), MUTED=Color.rgb(171,191,201), ACCENT=Color.rgb(156,232,213);
    private Ui() {}
    public static int dp(Context c,int n) { return (int)(n*c.getResources().getDisplayMetrics().density+0.5f); }
    public static LinearLayout column(Context c) { LinearLayout l=new LinearLayout(c); l.setOrientation(LinearLayout.VERTICAL); l.setPadding(dp(c,20),dp(c,16),dp(c,20),dp(c,16)); l.setBackgroundColor(BG); return l; }
    public static void inset(Activity a, View root) {
        root.setOnApplyWindowInsetsListener((v,i)-> { if(android.os.Build.VERSION.SDK_INT>=30) { android.graphics.Insets x=i.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()); v.setPadding(x.left,x.top,x.right,x.bottom); } else { v.setPadding(i.getSystemWindowInsetLeft(),i.getSystemWindowInsetTop(),i.getSystemWindowInsetRight(),i.getSystemWindowInsetBottom()); } return i; });
        root.requestApplyInsets();
    }
    public static TextView text(Context c,String s,int size,int color) { TextView t=new TextView(c); t.setText(s); t.setTextSize(size); t.setTextColor(color); t.setPadding(0,dp(c,7),0,dp(c,7)); t.setLineSpacing(dp(c,2),1.05f); return t; }
    public static void heading(LinearLayout l,String title,String body) { Context c=l.getContext(); TextView t=text(c,title,28,TEXT); t.setTypeface(null,Typeface.BOLD); l.addView(t); if(!body.isEmpty()) l.addView(text(c,body,15,MUTED)); }
    public static Button button(LinearLayout l,String label,boolean primary,Runnable action) {
        Context c=l.getContext(); Button b=new Button(c); b.setText(label); b.setAllCaps(false); b.setTextSize(15); b.setTextColor(primary?BG:TEXT); b.setMinHeight(dp(c,52));
        GradientDrawable g=new GradientDrawable(); g.setColor(primary?ACCENT:PANEL); g.setCornerRadius(dp(c,14)); b.setBackground(g);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.setMargins(0,dp(c,6),0,dp(c,6)); l.addView(b,p); b.setOnClickListener(v->action.run()); return b;
    }
    public static EditText input(LinearLayout l,String hint,int max) { Context c=l.getContext(); EditText e=new EditText(c); e.setSingleLine(true); e.setHint(hint); e.setTextColor(TEXT); e.setHintTextColor(MUTED); e.setTextSize(18); e.setMinHeight(dp(c,56)); e.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(max)}); l.addView(e,new LinearLayout.LayoutParams(-1,-2)); return e; }
    public static void message(Context c,String text) { Toast.makeText(c,text,Toast.LENGTH_LONG).show(); }
}
