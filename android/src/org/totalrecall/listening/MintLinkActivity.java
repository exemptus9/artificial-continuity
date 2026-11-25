package org.totalrecall.listening;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit opt-in for this phone to send private, saved clips to one pinned Mint receiver. */
@SuppressWarnings("deprecation")
public final class MintLinkActivity extends Activity {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final ExecutorService qr=Executors.newSingleThreadExecutor();
    private final AtomicBoolean decoding=new AtomicBoolean();
    private final Runnable tick=new Runnable() {
        @Override public void run() { status(); handler.postDelayed(this,2000); }
    };
    private LinearLayout page;
    private TextView state;
    private EditText input;
    private CheckBox existing;
    private Camera cam;
    private boolean pending;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        page();
    }
    @Override protected void onResume() { super.onResume(); handler.post(tick); }
    @Override protected void onPause() {
        handler.removeCallbacks(tick);
        release();
        super.onPause();
    }
    @Override protected void onDestroy() {
        io.shutdown(); qr.shutdownNow();
        super.onDestroy();
    }
    private int dp(int px) { return (int)(getResources().getDisplayMetrics().density*px+0.5f); }
    private TextView text(String message,int size) {
        TextView v=new TextView(this); v.setText(message); v.setTextSize(size); v.setPadding(0,dp(8),0,dp(8)); page.addView(v); return v;
    }
    private Button action(String label, View.OnClickListener click) {
        Button b=new Button(this); b.setText(label); b.setAllCaps(false); b.setOnClickListener(click); page.addView(b); return b;
    }
    private void page() {
        ScrollView scroll=new ScrollView(this);
        page=new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20),dp(26),dp(20),dp(20)); scroll.addView(page); setContentView(scroll);
        text("Connect to Mint",27);
        SharedPreferences prefs=MintSync.prefs(this);
        boolean linked=!prefs.getString("pair","").isEmpty();
        state=text("",16); status();
        if(linked) {
            action("Sync now", v -> { MintSync.cancel(this); MintSync.schedule(this); status(); });
            action("Disconnect", v -> new AlertDialog.Builder(this).setTitle("Disconnect this laptop?")
                    .setMessage("Future transfers will stop. Copies already saved on Mint stay there.")
                    .setPositiveButton("Disconnect",(d,w)->{MintSync.reset(this);page();})
                    .setNegativeButton("Cancel",null).show());
        } else {
            text("Open the Mint receiver on your laptop. Connect both devices to the same Wi-Fi, then scan its code.",16);
            action("Scan pairing code",v -> {
                if(checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) scan();
                else requestPermissions(new String[]{Manifest.permission.CAMERA},19);
            });
            text("Or paste the pairing code",15);
            input=new EditText(this); input.setSingleLine(); input.setHint("TR1.…");
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            input.setSaveEnabled(false);
            if (android.os.Build.VERSION.SDK_INT>=26) input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
            page.addView(input);
            existing=new CheckBox(this); existing.setText("Also send clips already saved on this phone"); page.addView(existing);
            text("Pairing automatically sends saved audio clips, transcripts and their catalog details to this laptop. New clips wait on your phone when Mint is offline.",15);
            action("Pair and enable transfers", v -> pair(input.getText().toString().trim(),existing.isChecked()));
        }
        action("Back to Total Recall", v->finish());
    }
    private void status() {
        if(state==null) return;
        SharedPreferences p=MintSync.prefs(this);
        String code=p.getString("pair","");
        if(code.isEmpty()) { state.setText("Local only · No laptop paired"); return; }
        try {
            state.setText("Paired with "+new MintSync.Pair(code).host+"\n"+p.getLong("receivedCount",0)
                    +" clips acknowledged · "+p.getString("status","Waiting to sync"));
        } catch (Exception e) { state.setText("Pairing details unavailable. Disconnect and pair again."); }
    }

    private void pair(String code, boolean includeExisting) {
        if(pending) return;
        final MintSync.Pair dest;
        try { dest=new MintSync.Pair(code); }
        catch(Exception error){ Toast.makeText(this,"Invalid pairing code. Copy or scan the complete code from Mint.",Toast.LENGTH_LONG).show();return; }
        String scope=includeExisting?"Existing and future saved clips":"Only clips saved after pairing";
        new AlertDialog.Builder(this).setTitle("Send clips to "+dest.host+"?")
            .setMessage(scope+" will transfer over your local network. Confirm that this is the code displayed on your laptop.")
            .setNegativeButton("Cancel",null).setPositiveButton("Pair",(dlg,w)->{
                pending=true;
                io.execute(()->{
                    try {
                        long after=0;
                        if(!includeExisting) try(Store db=new Store(this)) {
                            for(Store.Moment moment:db.search("")) after=Math.max(after,moment.id);
                        }
                        synchronized(MintSync.class) {
                            if(!MintSync.prefs(this).edit().clear().putString("pair",code).putLong("after",after)
                                    .putString("status","Waiting to sync").commit()) throw new java.io.IOException();
                        }
                        MintSync.schedule(this);
                        handler.post(()->{pending=false;if(!isDestroyed())page();});
                    } catch(Exception failure) {
                        handler.post(()->{pending=false;if(!isDestroyed())Toast.makeText(this,"Could not save pairing. Try again.",Toast.LENGTH_LONG).show();});
                    }
                });
            }).show();
    }
    @Override public void onRequestPermissionsResult(int req,String[] permissions,int[] result) {
        super.onRequestPermissionsResult(req,permissions,result);
        if(req==19 && result.length>0 && result[0]==PackageManager.PERMISSION_GRANTED) scan();
        else if(req==19) Toast.makeText(this,"You can paste the pairing code instead.",Toast.LENGTH_LONG).show();
    }
    private void scan() {
        final boolean include=existing.isChecked();
        LinearLayout box=new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        TextView hint=new TextView(this); hint.setText("Point at the QR code displayed on your Mint laptop."); hint.setPadding(dp(12),dp(16),dp(12),dp(8)); box.addView(hint);
        SurfaceView preview=new SurfaceView(this); box.addView(preview,new LinearLayout.LayoutParams(-1,0,1));
        Button cancel=new Button(this); cancel.setText("Cancel"); box.addView(cancel); cancel.setOnClickListener(v->{release();page();});
        preview.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) {
                try {
                    cam=Camera.open();
                    Camera.Parameters params=cam.getParameters();
                    List<String> focus=params.getSupportedFocusModes();
                    if(focus!=null && focus.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                        params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
                        cam.setParameters(params);
                    }
                    cam.setPreviewDisplay(holder);
                    if (getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_PORTRAIT) cam.setDisplayOrientation(90);
                    cam.setPreviewCallback((bytes,camera)->{
                        if(bytes==null || !decoding.compareAndSet(false,true)) return;
                        Camera.Size size=camera.getParameters().getPreviewSize();
                        qr.execute(()->{
                            String found=null;
                            try {
                                PlanarYUVLuminanceSource src=new PlanarYUVLuminanceSource(bytes,size.width,size.height,0,0,size.width,size.height,false);
                                String decoded=new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(src))).getText();
                                new MintSync.Pair(decoded); found=decoded;
                            } catch(Exception ignored) { }
                            final String code=found;
                            handler.post(()->{
                                decoding.set(false);
                                if(code!=null && !isDestroyed() && cam==camera) {
                                    release();page();existing.setChecked(include);input.setText(code);pair(code,include);
                                }
                            });
                        });
                    });
                    cam.startPreview();
                } catch(Exception e) { release(); handler.post(()->{if(!isDestroyed()){page();Toast.makeText(MintLinkActivity.this,"Camera unavailable. Paste the code instead.",Toast.LENGTH_LONG).show();}}); }
            }
            @Override public void surfaceChanged(SurfaceHolder holder,int format,int width,int height) {}
            @Override public void surfaceDestroyed(SurfaceHolder holder) {release();}
        });
        setContentView(box);
    }
    private void release() {
        if(cam!=null) {
            try {cam.setPreviewCallback(null);cam.stopPreview();} catch(RuntimeException ignored){}
            cam.release();cam=null;
        }
    }
}
