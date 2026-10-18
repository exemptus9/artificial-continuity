package com.intentintegrity.app;

import android.app.*;
import android.os.*;
import android.content.*;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

public final class MainActivity extends Activity {
    private LinearLayout page;
    private Spinner sites;
    private EditText query;
    private static final int EXPORT=21,IMPORT=22;
    @Override public void onCreate(Bundle state){super.onCreate(state);home();receive(getIntent());}
    @Override protected void onNewIntent(Intent i){super.onNewIntent(i);setIntent(i);home();receive(i);}
    private void receive(Intent i){
        String initial=i.getStringExtra("query");String site=i.getStringExtra("site");
        if(site!=null)for(int n=0;n<Routes.IDS.length;n++)if(Routes.IDS[n].equals(site))sites.setSelection(n);
        if(initial!=null)query.setText(initial.substring(0,Math.min(500,initial.length())));
        if(Intent.ACTION_SEND.equals(i.getAction())){String text=i.getStringExtra(Intent.EXTRA_TEXT);if(text!=null){String bounded=text.substring(0,Math.min(6000,text.length()));java.util.regex.Matcher m=java.util.regex.Pattern.compile("https://[^\\s<>]+",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(bounded);if(m.find() && !Routes.site(m.group()).isEmpty())offerLink(m.group());else query.setText(bounded.substring(0,Math.min(500,bounded.length())));}}
        if(i.getBooleanExtra("search_now",false) && initial!=null)search();
    }
    private void layout(){ScrollView sc=new ScrollView(this);sc.setFillViewport(true);sc.setBackgroundColor(Ui.BG);page=Ui.column(this);sc.addView(page);setContentView(sc);Ui.inset(this,sc);}
    private void home(){
        layout();page.addView(Ui.text(this,"INTENT INTEGRITY  /  0.2.0 PREVIEW",11,Ui.ACCENT));
        Ui.heading(page,"You get the first move.","Find what you came for. Keep the feed optional.");
        page.addView(Ui.text(this,"Search-first browser",14,Ui.ACCENT));
        sites=new Spinner(this);ArrayAdapter<String>a=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,Routes.NAMES);sites.setAdapter(a);sites.setMinimumHeight(Ui.dp(this,52));page.addView(sites);
        sites.setSelection(LocalData.prefs(this).getInt("site",0)%Routes.IDS.length);
        query=Ui.input(page,"What did you come to find?",500);query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);query.setOnEditorActionListener((v,id,e)->{if(id==EditorInfo.IME_ACTION_SEARCH){search();return true;}return false;});
        sites.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onNothingSelected(android.widget.AdapterView<?>p){}public void onItemSelected(android.widget.AdapterView<?>p,View v,int n,long id){query.setHint(n==6?"Instagram username (@name)":"What did you come to find?");LocalData.prefs(MainActivity.this).edit().putInt("site",n).apply();}});
        Ui.button(page,"Search without the home feed",true,this::search);
        Ui.button(page,"Open a direct link",false,this::linkEntry);
        Ui.button(page,"Later · "+LocalData.later(this).length()+" saved links",false,this::later);
        Ui.button(page,"Try the shield · offline preview",false,()->open("https://www.youtube.com/","Shield preview"));
        Ui.button(page,"Session length · "+minutes()+" minutes",false,()->new AlertDialog.Builder(this).setTitle("Time before a gentle checkpoint").setItems(new String[]{"5 minutes","10 minutes","20 minutes","30 minutes"},(d,n)->{LocalData.prefs(this).edit().putInt("minutes",new int[]{5,10,20,30}[n]).apply();home();}).show());
        if(BuildConfig.GUARD_ENABLED)Ui.button(page,"Protect installed apps · optional",false,()->startActivity(new Intent().setClassName(this,"com.intentintegrity.app.GuardSettingsActivity")));
        Ui.button(page,"Privacy, compatibility & backup",false,this::settings);
        page.addView(Ui.text(this,BuildConfig.GUARD_ENABLED?"Native app guard is OFF until you choose apps and grant access. Search mode works without accessibility permission.":"Core edition has no accessibility service. It does not alter other installed apps.",13,Ui.MUTED));
    }
    private int minutes(){int n=LocalData.prefs(this).getInt("minutes",10);return n==5||n==10||n==20||n==30?n:10;}
    private void search(){try{String q=query.getText().toString().trim();open(Routes.search(Routes.IDS[sites.getSelectedItemPosition()],q),q);}catch(Exception e){query.setError(e.getMessage());}}
    private void open(String url,String intent){startActivity(new Intent(this,BrowserActivity.class).putExtra("url",url).putExtra("intent",intent).putExtra("minutes",minutes()));}
    private void linkEntry(){EditText input=new EditText(this);input.setHint("https://…");input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);new AlertDialog.Builder(this).setTitle("Open a supported link").setMessage("Links are validated. Unknown destinations stay outside this browser.").setView(input).setPositiveButton("Review",(d,w)->offerLink(input.getText().toString().trim())).setNegativeButton("Cancel",null).show();}
    private void offerLink(String url){if(Routes.site(url).isEmpty()){Ui.message(this,"Use an HTTPS link from a supported platform.");return;}new AlertDialog.Builder(this).setTitle("Choose what happens").setMessage(url).setPositiveButton("Open protected",(d,w)->open(url,"Follow a chosen link")).setNeutralButton("Save for later",(d,w)->{try{LocalData.save(this,url,url);home();}catch(Exception e){Ui.message(this,e.getMessage());}}).setNegativeButton("Cancel",null).show();}
    private void later(){layout();Ui.heading(page,"Not now. Not lost.","Only links you explicitly saved live here. Nothing is uploaded to a developer server.");Ui.button(page,"Back to search",true,this::home);JSONArray a=LocalData.later(this);if(a.length()==0)page.addView(Ui.text(this,"Your Later shelf is empty. Use the Save button while browsing or share a link to this app.",16,Ui.MUTED));for(int n=0;n<a.length();n++){final int i=n;JSONObject x=a.optJSONObject(n);if(x==null)continue;String u=x.optString("url");Ui.button(page,x.optString("title",u),false,()->open(u,"Read a saved link"));page.addView(Ui.text(this,u,11,Ui.MUTED));Ui.button(page,"Remove saved link "+(n+1),false,()->{LocalData.remove(this,i);later();});}}
    private void settings(){layout();Ui.heading(page,"Your attention. Your data.","No account, advertising SDK, analytics SDK, cloud sync, microphone, contacts or location permission.");
        page.addView(Ui.text(this,"The selected website receives ordinary network requests, your searches, and anything you enter there. Website cookies and saved links stay in Android's private app storage; they are not separately encrypted by this app. Exported backups are plaintext. No automatic search history is kept by Intent Integrity. Sites and Android WebView may maintain their own data.",15,Ui.MUTED));
        page.addView(Ui.text(this,"Compatibility: these are website adapters, not partnerships with social platforms. Login, upload, playback or a changed layout may fail. Instagram currently opens a chosen profile, not general topic search. Search results may still be ranked by algorithms. A chosen profile, community or messages page can still contain recommendations. This app is not an ad blocker, a content classifier or a medical treatment.",15,Ui.MUTED));
        Ui.button(page,"Export Later backup",false,()->{Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"intent-integrity-later.json");startActivityForResult(i,EXPORT);});
        Ui.button(page,"Import Later backup",false,()->new AlertDialog.Builder(this).setTitle("Replace the Later shelf?").setMessage("A valid backup replaces saved links, not permissions or protection settings. Export your current shelf first.").setPositiveButton("Choose backup",(d,w)->startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE),IMPORT)).setNegativeButton("Cancel",null).show());
        Ui.button(page,"Clear website logins and cache",false,()->new AlertDialog.Builder(this).setTitle("Sign out of websites?").setMessage("Removes this app's website cookies, cache and web storage. Saved links stay.").setPositiveButton("Clear",(d,w)->{android.webkit.CookieManager.getInstance().removeAllCookies(ok->Ui.message(this,"Website cookies cleared."));android.webkit.WebStorage.getInstance().deleteAllData();android.webkit.WebView v=new android.webkit.WebView(this);v.clearCache(true);v.clearHistory();v.destroy();}).setNegativeButton("Cancel",null).show());
        Ui.button(page,"Delete all saved links",false,()->new AlertDialog.Builder(this).setTitle("Delete the Later shelf?").setPositiveButton("Delete",(d,w)->{LocalData.prefs(this).edit().remove("later").commit();Ui.message(this,"Saved links deleted.");}).setNegativeButton("Cancel",null).show());
        Ui.button(page,"Run offline diagnostics",false,()->{int passed=0;for(String s:Routes.IDS)try{String u=Routes.search(s,s.equals("instagram")?"example":"test");if(Routes.allowed(u))passed++;}catch(Exception ignored){}new AlertDialog.Builder(this).setTitle("Local checks").setMessage("Search route checks: "+passed+" / 7\nAndroid API: "+Build.VERSION.SDK_INT+"\nVersion: "+BuildConfig.VERSION_NAME+"\nEdition: "+BuildConfig.FLAVOR+"\n\nThese are rule checks, not proof that a platform's live website or native app is compatible.").setPositiveButton("Close",null).show();});
        Ui.button(page,"Back to search",true,this::home);
    }
    @Override protected void onActivityResult(int code,int result,Intent data){super.onActivityResult(code,result,data);if(result!=RESULT_OK || data==null || data.getData()==null)return;try{if(code==EXPORT){try(OutputStream out=getContentResolver().openOutputStream(data.getData())){if(out==null)throw new IOException("Cannot open destination.");out.write(LocalData.export(this).getBytes(StandardCharsets.UTF_8));}Ui.message(this,"Backup exported. It contains plaintext saved links.");}else if(code==IMPORT){String text;try(InputStream in=getContentResolver().openInputStream(data.getData());ByteArrayOutputStream out=new ByteArrayOutputStream()){if(in==null)throw new IOException("Cannot open backup.");byte[] b=new byte[8192];int n,total=0;while((n=in.read(b))!=-1){total+=n;if(total>524288)throw new IOException("Backup exceeds 512 KB.");out.write(b,0,n);}text=out.toString(StandardCharsets.UTF_8);}Ui.message(this,"Restored "+LocalData.restore(this,text)+" saved links.");}}catch(Exception e){Ui.message(this,"No import applied: "+e.getMessage());}}
}
