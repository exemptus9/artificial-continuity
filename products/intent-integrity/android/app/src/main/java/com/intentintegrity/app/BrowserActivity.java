package com.intentintegrity.app;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.os.*;
import android.view.*;
import android.webkit.*;
import android.widget.*;

/** Protected WebView with native controls, no JavaScript-to-native interface. */
public final class BrowserActivity extends Activity {
    private WebView web;
    private FrameLayout stage;
    private LinearLayout gate;
    private TextView status;
    private String pending="", intention="", lastChecked="";
    private final Lease reveal=new Lease();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private long deadline;
    private boolean active,checkpoint,externalDialog,failed;
    private int generation;
    private final Runnable tick=new Runnable(){public void run(){if(!active)return;String u=web.getUrl();if(u!=null && !u.equals(lastChecked)){lastChecked=u;check(u);}if(gate==null && !pending.isEmpty() && !Routes.allowed(pending) && !reveal.valid(Routes.pageKey(pending),SystemClock.elapsedRealtime()))showGate(pending,"Your reveal pass ended.");if(!checkpoint && SystemClock.elapsedRealtime()>=deadline){checkpoint=true;pauseMedia();new AlertDialog.Builder(BrowserActivity.this).setTitle("Did you get what you came for?").setMessage(intention).setCancelable(false).setPositiveButton("Done",(d,w)->finish()).setNegativeButton("Five more minutes",(d,w)->{deadline=SystemClock.elapsedRealtime()+300000;checkpoint=false;}).show();}long secs=Math.max(0,(deadline-SystemClock.elapsedRealtime())/1000);status.setText("YOU CAME TO: "+intention+"\n"+secs/60+":"+String.format(java.util.Locale.ROOT,"%02d",secs%60)+" until checkpoint");handler.postDelayed(this,750);}};
    @Override public void onCreate(Bundle state){super.onCreate(state);intention=getIntent().getStringExtra("intent");if(intention==null)intention="Follow a chosen link";if(intention.length()>500)intention=intention.substring(0,500);deadline=SystemClock.elapsedRealtime()+Math.max(1,Math.min(30,getIntent().getIntExtra("minutes",10)))*60000L;
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Ui.BG);LinearLayout controls=Ui.column(this);controls.setPadding(Ui.dp(this,12),Ui.dp(this,4),Ui.dp(this,12),Ui.dp(this,4));status=Ui.text(this,"",12,Ui.ACCENT);controls.addView(status);
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);String[] names={"Search","Back","Save","More"};for(int n=0;n<names.length;n++){final int i=n;Button b=new Button(this);b.setText(names[n]);b.setAllCaps(false);b.setTextSize(12);b.setMinHeight(Ui.dp(this,48));row.addView(b,new LinearLayout.LayoutParams(0,-2,1));b.setOnClickListener(v->{if(i==0)finish();else if(i==1)back();else if(i==2)save();else more();});}controls.addView(row);root.addView(controls);
        stage=new FrameLayout(this);root.addView(stage,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);Ui.inset(this,root);
        web=new WebView(this);web.setBackgroundColor(Ui.BG);stage.addView(web,new FrameLayout.LayoutParams(-1,-1));WebSettings s=web.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setAllowFileAccess(false);s.setAllowContentAccess(false);s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);s.setMediaPlaybackRequiresUserGesture(true);s.setGeolocationEnabled(false);s.setSafeBrowsingEnabled(true);s.setSupportMultipleWindows(false);s.setJavaScriptCanOpenWindowsAutomatically(false);CookieManager.getInstance().setAcceptThirdPartyCookies(web,false);
        web.setWebChromeClient(new WebChromeClient(){@Override public void onPermissionRequest(PermissionRequest r){r.deny();}@Override public void onGeolocationPermissionsShowPrompt(String origin,GeolocationPermissions.Callback cb){cb.invoke(origin,false,false);}});
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest req){if(!req.isForMainFrame())return false;String u=req.getUrl().toString();if(Routes.site(u).isEmpty()){external(u);return true;}if(blocked(u)){showGate(u,"A feed or unrecognized page is asking for your attention.");return true;}pending=u;return false;}
            @Override public void onPageStarted(WebView v,String u,Bitmap f){generation++;failed=false;v.setVisibility(View.INVISIBLE);pending=u;if(Routes.site(u).isEmpty()){v.stopLoading();showGate(u,"This destination is outside the supported platforms.");}else if(blocked(u)){v.stopLoading();showGate(u,"Choose before the feed appears.");}}
            @Override public void onPageCommitVisible(WebView v,String u){decorate(u);}
            @Override public void onPageFinished(WebView v,String u){if(!failed)decorate(u);}
            @Override public void doUpdateVisitedHistory(WebView v,String u,boolean reload){check(u);}
            @Override public void onReceivedSslError(WebView v,SslErrorHandler h,SslError e){h.cancel();failed=true;showGate(pending,"Secure connection failed. Certificate errors are never bypassed.");}
            @Override public void onReceivedError(WebView v,WebResourceRequest r,WebResourceError e){if(r.isForMainFrame()){failed=true;showGate(pending,"The website could not be loaded. Check your connection or try its ordinary browser version.");}}
        });
        String u=getIntent().getStringExtra("url");navigate(u==null?"https://www.youtube.com/":u);
    }
    private boolean blocked(String u){return !Routes.allowed(u) && !reveal.valid(Routes.pageKey(u),SystemClock.elapsedRealtime());}
    private void navigate(String u){pending=u;if(Routes.site(u).isEmpty() || blocked(u)){showGate(u,"The feed stays hidden until you choose.");return;}removeGate();web.setVisibility(View.INVISIBLE);web.loadUrl(u);}
    private void check(String u){if(u==null)return;if(Routes.site(u).isEmpty() || blocked(u)){pending=u;showGate(u,"This route is held by search-first mode.");}else if(gate!=null && u.equals(pending))decorate(u);}
    private void decorate(String u){if(u==null || failed || !u.equals(web.getUrl()))return;if(Routes.site(u).isEmpty() || blocked(u)){showGate(u,"This route needs an explicit choice.");return;}final int stamp=generation;boolean bypass=reveal.valid(Routes.pageKey(u),SystemClock.elapsedRealtime());String script="(()=>{let x=document.getElementById('ii-clean-css');if(!x){x=document.createElement('style');x.id='ii-clean-css';document.documentElement.append(x);}x.textContent="+org.json.JSONObject.quote(bypass?"":"ytd-watch-next-secondary-results-renderer,#secondary.ytd-watch-flexy,ytd-reel-shelf-renderer,ytm-reel-shelf-renderer,.ytp-endscreen-content{display:none!important}")+";document.querySelectorAll('video,audio').forEach(m=>{m.autoplay=false;m.removeAttribute('autoplay')});return true})()";web.evaluateJavascript(script,result->{if(stamp==generation && u.equals(web.getUrl()) && !blocked(u) && !failed){removeGate();web.setVisibility(View.VISIBLE);}});}
    private void showGate(String u,String reason){pending=u;web.setVisibility(View.INVISIBLE);pauseMedia();if(gate!=null)stage.removeView(gate);gate=Ui.column(this);gate.setGravity(Gravity.CENTER_VERTICAL);Ui.heading(gate,"Feed held at the door.",reason);gate.addView(Ui.text(this,"You came to: "+intention,16,Ui.ACCENT));gate.addView(Ui.text(this,Routes.site(u).isEmpty()?"Unsupported destination":Routes.site(u),12,Ui.MUTED));Ui.button(gate,"Back to search",true,this::finish);if(!Routes.site(u).isEmpty())Ui.button(gate,"Reveal this page · 60 seconds",false,()->new AlertDialog.Builder(this).setTitle("Reveal this page?").setMessage("This deliberately permits the feed or unknown page. It is not search-only during the pass.").setPositiveButton("Reveal for 60s",(d,w)->{reveal.grant(Routes.pageKey(pending),SystemClock.elapsedRealtime(),60000);String next=pending;removeGate();web.loadUrl(next);}).setNegativeButton("Cancel",null).show());Ui.button(gate,"Save this link for later",false,this::save);stage.addView(gate,new FrameLayout.LayoutParams(-1,-1));}
    private void removeGate(){if(gate!=null){stage.removeView(gate);gate=null;}}
    private void pauseMedia(){if(web!=null)web.evaluateJavascript("document.querySelectorAll('video,audio').forEach(m=>m.pause())",null);}
    private void save(){try{LocalData.save(this,pending,web.getTitle()==null?pending:web.getTitle());Ui.message(this,"Saved for later.");}catch(Exception e){Ui.message(this,e.getMessage());}}
    private void more(){new AlertDialog.Builder(this).setTitle("You control the exit").setItems(new String[]{"Reload protected page","Open in ordinary browser (not protected)","End session"},(d,n)->{if(n==0)navigate(pending);else if(n==1)external(pending);else finish();}).show();}
    private void external(String u){if(externalDialog)return;Uri uri;try{uri=Uri.parse(u);}catch(Exception e){return;}if(!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null){Ui.message(this,"Non-HTTPS app redirects are blocked.");return;}externalDialog=true;new AlertDialog.Builder(this).setTitle("Leave protected browsing?").setMessage("This opens another app. Intent Integrity's browser protection will no longer apply.\n\n"+uri.getHost()).setPositiveButton("Open externally",(d,w)->{try{startActivity(new Intent(Intent.ACTION_VIEW,uri).addCategory(Intent.CATEGORY_BROWSABLE));}catch(ActivityNotFoundException e){Ui.message(this,"No browser available.");}}).setNegativeButton("Stay here",null).setOnDismissListener(d->externalDialog=false).show();}
    private void back(){reveal.clear();if(web.canGoBack()){removeGate();web.goBack();}else finish();}
    @Override public void onBackPressed(){back();}
    @Override protected void onResume(){super.onResume();active=true;if(web!=null)web.onResume();handler.post(tick);}
    @Override protected void onPause(){active=false;handler.removeCallbacks(tick);pauseMedia();if(web!=null)web.onPause();super.onPause();}
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);if(web!=null){stage.removeView(web);web.destroy();}super.onDestroy();}
}
