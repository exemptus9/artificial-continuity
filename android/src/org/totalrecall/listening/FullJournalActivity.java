package org.totalrecall.listening;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.os.*;
import android.widget.*;
import android.text.SpannableStringBuilder;
import android.text.style.ClickableSpan;
import java.text.DateFormat;
import java.util.*;
import java.util.concurrent.*;

/** A keyset-paged, live reader for recognized speech across all retained sessions. */
public final class FullJournalActivity extends Activity {
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final List<Long> pages=new ArrayList<>();
    private TextView transcript,hearing;
    private Button older,newer;
    private long next,nextRefresh;
    private int generation;
    private boolean visible,busy,showOriginal;
    private final Runnable poll=new Runnable(){public void run(){
        if(!visible)return;
        LiveCapture.Snapshot live=LiveCapture.snapshot();
        String words=live.active&&!live.waiting?live.partial:"";
        hearing.setText(words.isEmpty()?"":"Hearing now · "+words);
        hearing.setVisibility(words.isEmpty()?android.view.View.GONE:android.view.View.VISIBLE);
        if(pages.size()==1&&!busy)read();
        main.postDelayed(this,2400);
    }};
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);setTitle("Full journal");
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(dp(15),dp(10),dp(15),dp(12));setContentView(page);
        if(Build.VERSION.SDK_INT>=35)page.setOnApplyWindowInsetsListener((v,i)->{
            android.graphics.Insets b=i.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout());
            v.setPadding(dp(15)+b.left,dp(10)+b.top,dp(15)+b.right,dp(12)+b.bottom);return i;
        });
        TextView title=new TextView(this);title.setText("Full journal");title.setTextSize(26);page.addView(title);
        Switch save=new Switch(this);save.setText("Save all recognized speech on this phone");save.setMinHeight(dp(48));
        save.setChecked(JournalStore.enabled(this));page.addView(save);
        save.setOnClickListener(v->{try{JournalStore.setEnabled(this,save.isChecked());read();}
            catch(RuntimeException e){save.setChecked(JournalStore.enabled(this));Toast.makeText(this,"Could not update journal saving",Toast.LENGTH_LONG).show();}});
        Switch original=new Switch(this);original.setText("Compare with original recognition");page.addView(original);
        original.setOnCheckedChangeListener((v,on)->{showOriginal=on;read();});
        LinearLayout tools=new LinearLayout(this);page.addView(tools);
        button(tools,"My words & phrases",()->SpellingPreferences.open(this));
        Button sessions=new Button(this);sessions.setText("Sessions, export & retention");
        sessions.setOnClickListener(v->startActivity(new Intent(this,RecallListenActivity.class).putExtra("captureDestination","journalOptions")));tools.addView(sessions,new LinearLayout.LayoutParams(0,-2,1));
        TextView hint=new TextView(this);hint.setText("Tap a passage to correct, copy or share it.");page.addView(hint);
        LinearLayout nav=new LinearLayout(this);page.addView(nav);
        newer=button(nav,"Newer lines",()->{if(pages.size()>1){pages.remove(pages.size()-1);read();}});
        older=button(nav,"Older lines",()->{if(next>0){pages.add(next);read();}});
        hearing=new TextView(this);hearing.setMaxLines(3);hearing.setTextSize(16);page.addView(hearing);
        ScrollView sc=new ScrollView(this);page.addView(sc);transcript=new TextView(this);
        transcript.setTextSize(17);transcript.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());sc.addView(transcript);pages.add(Long.MAX_VALUE);
    }
    private Button button(LinearLayout row,String text,Runnable action){
        Button b=new Button(this);b.setText(text);b.setOnClickListener(v->action.run());row.addView(b,new LinearLayout.LayoutParams(0,-2,1));return b;
    }
    @Override public void onResume(){super.onResume();visible=true;RecallOverlay.reviewVisible(this,true);read();main.post(poll);}
    @Override public void onPause(){visible=false;generation++;busy=false;main.removeCallbacks(poll);RecallOverlay.reviewVisible(this,false);super.onPause();}
    @Override public void onDestroy(){io.shutdownNow();super.onDestroy();}
    private void editLine(long id,String wording,String raw){
        LinearLayout fields=new LinearLayout(this);fields.setOrientation(LinearLayout.VERTICAL);fields.setPadding(dp(18),dp(8),dp(18),dp(4));
        TextView heard=new TextView(this);heard.setText("Originally heard\n"+raw);heard.setTextIsSelectable(true);fields.addView(heard);
        TextView title=new TextView(this);title.setText("What I actually said");fields.addView(title);
        EditText field=new EditText(this);field.setSingleLine(true);field.setText(wording);field.setContentDescription("What I actually said");fields.addView(field);
        LinearLayout actions=new LinearLayout(this);fields.addView(actions);
        button(actions,"Copy words",()->PassageActions.copy(this,field.getText().toString()));
        button(actions,"Share words",()->PassageActions.share(this,field.getText().toString()));
        ScrollView scroll=new ScrollView(this);scroll.addView(fields);
        android.app.AlertDialog dialog=new android.app.AlertDialog.Builder(this).setTitle("Journal passage").setView(scroll)
            .setNegativeButton("Close",null).setPositiveButton("Save correction",null).create();
        dialog.show();Button save=dialog.getButton(-1);
        save.setOnClickListener(v->{String replacement=field.getText().toString().trim();
            if(replacement.isEmpty()){field.setError("Enter the words you want to keep");return;}
            save.setEnabled(false);io.execute(()->{try(JournalStore db=new JournalStore(this)){
                db.correctLine(id,wording,replacement);
                main.post(()->{if(isDestroyed())return;dialog.dismiss();if(visible)read();Toast.makeText(this,"Journal correction saved",Toast.LENGTH_SHORT).show();});
            }catch(RuntimeException e){main.post(()->{if(!isDestroyed()){save.setEnabled(true);field.setError(e.getMessage());}});}});
        });
    }
    private void read(){
        int request=++generation;long before=pages.get(pages.size()-1);boolean compare=showOriginal;busy=true;older.setEnabled(false);newer.setEnabled(pages.size()>1);
        io.execute(()->{
            long boundary=0;SpannableStringBuilder result=new SpannableStringBuilder();String error=null;
            try(JournalStore db=new JournalStore(this)){
                db.prune();
                try(Cursor c=db.getReadableDatabase().rawQuery("SELECT l.id,l.at,l.text,l.is_partial,l.voice,v.name,v.source,l.original FROM lines l LEFT JOIN journal_voices v ON v.session=l.session AND v.voice=l.voice WHERE l.id<? ORDER BY l.id DESC LIMIT 41",new String[]{String.valueOf(before)})){
                    int count=0;long last=0;DateFormat format=DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.MEDIUM);
                    while(c.moveToNext()){
                        if(count++==40){boundary=last;break;}last=c.getLong(0);
                        String who=JournalStore.voiceLabel(c.getInt(4),c.getString(5),c.getString(6));
                        int start=result.length();
                        result.append(format.format(new Date(c.getLong(1)))).append(who.isEmpty()?"":" · "+who)
                            .append(c.getInt(3)==1?" · unfinished":c.getInt(3)==2?" · capture status":"").append('\n').append(!compare&&c.getInt(3)==0?SpeechContext.reading(c.getString(2)):c.getString(2));
                        if(compare&&!c.isNull(7))result.append("\nOriginally heard · ").append(c.getString(7));
                        if(c.getInt(3)!=2){
                            long row=c.getLong(0);String wording=c.getString(2),raw=c.isNull(7)?wording:c.getString(7);
                            result.setSpan(new ClickableSpan(){
                                public void onClick(android.view.View view){editLine(row,wording,raw);}
                                @Override public void updateDrawState(android.text.TextPaint paint){paint.setColor(0xff16483c);}
                            },start,result.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        }
                        result.append("\n\n");
                    }
                }
            }catch(RuntimeException failure){error="Could not read the journal.";}
            final long mark=boundary;if(result.length()>=2)result.delete(result.length()-2,result.length());
            final CharSequence body=error!=null?error:result;
            main.post(()->{if(!visible||request!=generation)return;busy=false;next=mark;older.setEnabled(next>0);
                CharSequence value=body.length()==0?(JournalStore.enabled(this)?"Journal saving is on. Recognized lines will appear here while listening.":"Journal saving is off. Turn it on above to keep future words.") :body;
                if(!android.text.TextUtils.equals(transcript.getText(),value))transcript.setText(value);
            });
        });
    }
}
