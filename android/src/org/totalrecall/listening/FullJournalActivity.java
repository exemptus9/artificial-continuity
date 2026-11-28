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
    private ScrollView journalScroll;
    private int restoreScroll;
    private JournalSegmentPlayer segmentPlayer;
    private Button older,newer,review;
    private long next,nextRefresh;
    private String search="";
    private final Runnable searchRefresh=()->{if(this.visible)read();};
    private int generation;
    private boolean visible,busy,showOriginal,reviewOnly;
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
        super.onCreate(state);setTitle("Full journal");reviewOnly=state!=null && state.getBoolean("reviewOnly");
        showOriginal=state!=null && state.getBoolean("showOriginal");
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(dp(15),dp(10),dp(15),dp(12));setContentView(page);
        if(Build.VERSION.SDK_INT>=35)page.setOnApplyWindowInsetsListener((v,i)->{
            android.graphics.Insets b=i.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout());
            v.setPadding(dp(15)+b.left,dp(10)+b.top,dp(15)+b.right,dp(12)+b.bottom);return i;
        });
        LinearLayout header=new LinearLayout(this);page.addView(header);
        TextView title=new TextView(this);title.setText("Full journal");title.setTextSize(26);header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        Button options=new Button(this);options.setAllCaps(false);options.setText("Chapters & sync");options.setOnClickListener(v->chapterOptions());header.addView(options);
        SearchView find=new SearchView(this);find.setQueryHint("Find saved or originally heard words");page.addView(find);
        if(state!=null){search=state.getString("search","");if(!search.isEmpty()){find.setIconified(false);find.setQuery(search,false);find.clearFocus();}}
        find.setOnQueryTextListener(new SearchView.OnQueryTextListener(){
            public boolean onQueryTextSubmit(String text){find.clearFocus();return true;}
            public boolean onQueryTextChange(String text){
                search=text.trim();pages.clear();pages.add(Long.MAX_VALUE);
                generation++;busy=false;restoreScroll=0;if(journalScroll!=null)journalScroll.scrollTo(0,0);
                main.removeCallbacks(searchRefresh);main.postDelayed(searchRefresh,180);return true;
            }
        });
        Switch save=new Switch(this);save.setText("Save all recognized speech on this phone");save.setMinHeight(dp(48));
        save.setChecked(JournalStore.enabled(this));page.addView(save);
        save.setOnClickListener(v->{try{JournalStore.setEnabled(this,save.isChecked());read();}
            catch(RuntimeException e){save.setChecked(JournalStore.enabled(this));Toast.makeText(this,"Could not update journal saving",Toast.LENGTH_LONG).show();}});
        Switch original=new Switch(this);original.setText("Compare with original recognition");original.setChecked(showOriginal);page.addView(original);
        original.setOnCheckedChangeListener((v,on)->{showOriginal=on;read();});
        LinearLayout tools=new LinearLayout(this);page.addView(tools);
        button(tools,"My words & phrases",()->SpellingPreferences.open(this));
        Button sessions=new Button(this);sessions.setText("Sessions, export & retention");
        sessions.setOnClickListener(v->startActivity(new Intent(this,RecallListenActivity.class).putExtra("captureDestination","journalOptions")));tools.addView(sessions,new LinearLayout.LayoutParams(0,-2,1));
        review=new Button(this);review.setAllCaps(false);review.setVisibility(android.view.View.GONE);page.addView(review);
        review.setOnClickListener(v->{reviewOnly=!reviewOnly;pages.clear();pages.add(Long.MAX_VALUE);read();});
        LinearLayout nav=new LinearLayout(this);page.addView(nav);
        newer=button(nav,"Newer lines",()->{if(pages.size()>1){pages.remove(pages.size()-1);journalScroll.scrollTo(0,0);read();}});
        older=button(nav,"Older lines",()->{if(next>0){pages.add(next);journalScroll.scrollTo(0,0);read();}});
        hearing=new TextView(this);hearing.setMaxLines(3);hearing.setTextSize(16);page.addView(hearing);
        journalScroll=new ScrollView(this);page.addView(journalScroll);transcript=new TextView(this);
        transcript.setTextSize(17);transcript.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());journalScroll.addView(transcript);
        long[] history=state==null?null:state.getLongArray("pages");
        if(history!=null)for(long before:history)pages.add(before);
        if(pages.isEmpty())pages.add(Long.MAX_VALUE);
        restoreScroll=state==null?0:state.getInt("journalScroll");
    }
    private void chapterOptions(){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(18),dp(8),dp(18),dp(8));
        TextView hint=new TextView(this);
        hint.setText("A chapter files finished words while the microphone keeps listening. Your archive transfer settings control when it reaches another device.");box.addView(hint);
        CheckBox automatic=new CheckBox(this);automatic.setText("Save five-minute chapters at the next finished phrase");
        automatic.setChecked(JournalStore.chapters(this));box.addView(automatic);
        automatic.setOnCheckedChangeListener((v,on)->JournalStore.setChapters(this,on));
        Button file=new Button(this);file.setText("Save chapter now");box.addView(file);
        file.setOnClickListener(v->{
            long session=LiveCapture.snapshot().journalId,epoch=JournalStore.epoch(this);file.setEnabled(false);
            io.execute(()->{String message;
                try(JournalStore store=new JournalStore(this)){
                    message=store.saveChapter(session,epoch,0)>=0?"Chapter saved on this phone.":"A chapter needs an active journal with a finished phrase.";
                }catch(RuntimeException e){message="Could not save this chapter. Try again.";}
                String result=message;main.post(()->{if(!isDestroyed()){file.setEnabled(true);hint.setText(result);hint.announceForAccessibility(result);if(visible)read();}});
            });
        });
        Button shared=new Button(this);shared.setText("Read shared journal");box.addView(shared);
        shared.setOnClickListener(v->{try{startActivity(new Intent(Intent.ACTION_VIEW,android.net.Uri.parse(ArchiveDestination.WEBSITE+"/journal")));}
            catch(android.content.ActivityNotFoundException e){hint.setText("Install a browser to open the shared journal.");}});
        Button sync=new Button(this);sync.setText("Archive transfer settings");sync.setOnClickListener(v->startActivity(new Intent(this,ArchiveSyncActivity.class)));box.addView(sync);
        ScrollView scroll=new ScrollView(this);scroll.addView(box);
        new android.app.AlertDialog.Builder(this).setTitle("Journal chapters").setView(scroll).setNegativeButton("Close",null).show();
    }
    private Button button(LinearLayout row,String text,Runnable action){
        Button b=new Button(this);b.setText(text);b.setOnClickListener(v->action.run());row.addView(b,new LinearLayout.LayoutParams(0,-2,1));return b;
    }
    @Override public void onResume(){super.onResume();visible=true;RecallOverlay.reviewVisible(this,true);read();main.post(poll);}
    @Override public void onPause(){if(segmentPlayer!=null){segmentPlayer.close();segmentPlayer=null;}visible=false;generation++;busy=false;main.removeCallbacks(poll);main.removeCallbacks(searchRefresh);RecallOverlay.reviewVisible(this,false);super.onPause();}
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putString("search",search);state.putBoolean("reviewOnly",reviewOnly);state.putBoolean("showOriginal",showOriginal);
        long[] history=new long[pages.size()];for(int i=0;i<history.length;i++)history[i]=pages.get(i);
        state.putLongArray("pages",history);state.putInt("journalScroll",journalScroll.getScrollY());
    }
    @Override public void onDestroy(){io.shutdownNow();super.onDestroy();}
    private void editLine(long id,String wording,String raw,String uncertain,long at){
        LinearLayout fields=new LinearLayout(this);fields.setOrientation(LinearLayout.VERTICAL);fields.setPadding(dp(18),dp(8),dp(18),dp(4));
        TextView heard=new TextView(this);heard.setText("Originally heard\n"+raw);heard.setTextIsSelectable(true);fields.addView(heard);
        if(!uncertain.isEmpty()){
            TextView check=new TextView(this);check.setText("Check these words · "+uncertain);fields.addView(check);
        }
        TextView title=new TextView(this);title.setText("What I actually said");fields.addView(title);
        EditText field=new EditText(this);field.setSingleLine(true);field.setText(wording);field.setContentDescription("What I actually said");fields.addView(field);
        LinearLayout actions=new LinearLayout(this);fields.addView(actions);
        button(actions,"Copy words",()->PassageActions.copy(this,field.getText().toString()));
        button(actions,"Share words",()->PassageActions.share(this,field.getText().toString()));
        Button teach = new Button(this);
        teach.setText("Teach this spelling");
        teach.setOnClickListener(v -> SpellingPreferences.fromCorrection(this, raw, field));
        fields.addView(teach);
        CheckBox leadIn=new CheckBox(this);leadIn.setText("Include 6 seconds before the timestamp");leadIn.setChecked(true);fields.addView(leadIn);
        Button nearby=new Button(this);nearby.setAllCaps(false);nearby.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);
        nearby.setText("Find audio at this time");fields.addView(nearby);
        nearby.setOnClickListener(v->findAudio(id,at,leadIn.isChecked(),nearby));
        ScrollView scroll=new ScrollView(this);scroll.addView(fields);
        android.app.AlertDialog.Builder build=new android.app.AlertDialog.Builder(this).setTitle("Journal passage").setView(scroll)
            .setNegativeButton("Close",null).setPositiveButton("Save correction",null);
        if(!uncertain.isEmpty())build.setNeutralButton("Wording is right",null);
        android.app.AlertDialog dialog=build.create();dialog.show();Button save=dialog.getButton(-1),right=dialog.getButton(-3);
        if(!uncertain.isEmpty()){
            save.setEnabled(false);
            field.addTextChangedListener(new android.text.TextWatcher(){
                public void beforeTextChanged(CharSequence s,int start,int count,int after){}
                public void onTextChanged(CharSequence s,int start,int before,int count){boolean same=wording.equals(s.toString().trim());right.setEnabled(same);save.setEnabled(!same);}
                public void afterTextChanged(android.text.Editable ignored){}
            });
            right.setOnClickListener(v->{right.setEnabled(false);field.setEnabled(false);io.execute(()->{try(JournalStore db=new JournalStore(this)){
                db.confirmWording(id,wording);
                main.post(()->{if(!isDestroyed()){dialog.dismiss();if(visible)read();}});
            }catch(RuntimeException e){main.post(()->{if(!isDestroyed()){right.setEnabled(true);field.setEnabled(true);field.setError(e.getMessage());}});}});});
        }
        save.setOnClickListener(v->{String replacement=field.getText().toString().trim();
            if(replacement.isEmpty()){field.setError("Enter the words you want to keep");return;}
            save.setEnabled(false);field.setEnabled(false);io.execute(()->{try(JournalStore db=new JournalStore(this)){
                db.correctLine(id,wording,replacement);
                main.post(()->{if(isDestroyed())return;dialog.dismiss();if(visible)read();Toast.makeText(this,"Journal correction saved",Toast.LENGTH_SHORT).show();});
            }catch(RuntimeException e){main.post(()->{if(!isDestroyed()){save.setEnabled(true);field.setEnabled(true);field.setError(e.getMessage());}});}});
        });
    }
    private void findAudio(long row,long at,boolean leadIn,Button button){
        button.setEnabled(false);button.setText("Checking audio on this phone…");
        io.execute(()->{
            try{
                if(!ArchiveStore.storageUnlocked(this))throw new java.io.IOException("Unlock this phone to check saved audio.");
                try(JournalStore journal=new JournalStore(this)){
                    journal.prune();
                    try(Cursor c=journal.getReadableDatabase().rawQuery("SELECT 1 FROM lines WHERE id=? AND at=? AND is_partial<>2",new String[]{Long.toString(row),Long.toString(at)})){
                        if(!c.moveToFirst())throw new java.io.IOException("This journal passage expired or was deleted.");
                    }
                }
                Store.Moment clip;
                try(Store clips=new Store(this)){clip=clips.covering(at);}
                ArchiveStore.Segment segment=null;
                if(clip==null)try(ArchiveStore archive=new ArchiveStore(this)){segment=archive.covering(at);}
                if(clip==null && segment==null)throw new java.io.IOException("No saved audio on this phone covers this time.");
                ArchiveStore.Segment recording=segment;
                long from=Math.max(0,at-(clip!=null?clip.startMs:segment.startMs)-(leadIn?6000:0));
                main.post(()->{
                    if(!visible || isDestroyed() || !button.isAttachedToWindow())return;
                    button.setText("Find audio at this time");button.setEnabled(true);
                    if(clip!=null)startActivity(new Intent(this,RecallListenActivity.class).putExtra("moment_id",clip.id)
                        .putExtra("clip_offset_ms",from).putExtra("return_to_journal",true));
                    else{
                        if(segmentPlayer!=null)segmentPlayer.close();
                        segmentPlayer=new JournalSegmentPlayer(this,io,recording,from);segmentPlayer.show();
                    }
                });
            }catch(java.io.IOException|RuntimeException error){
                String message=error instanceof java.io.IOException?error.getMessage():"Could not read phone storage. Tap to try again.";
                main.post(()->{if(visible && button.isAttachedToWindow()){button.setText(message);button.setEnabled(true);}});
            }
        });
    }
    private void read(){
        String needle=search;int request=++generation;long before=pages.get(pages.size()-1);boolean compare=showOriginal,filter=reviewOnly;busy=true;older.setEnabled(false);newer.setEnabled(pages.size()>1);
        io.execute(()->{
            int needsReview=0;long boundary=0;SpannableStringBuilder result=new SpannableStringBuilder();String error=null;
            try(JournalStore db=new JournalStore(this)){
                db.prune();
                try(Cursor n=db.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM lines WHERE check_words<>''",null)){
                    if(n.moveToFirst())needsReview=n.getInt(0);
                }
                try(Cursor c=db.getReadableDatabase().rawQuery("SELECT l.id,l.at,l.text,l.is_partial,l.voice,v.name,v.source,l.original,l.check_words FROM lines l LEFT JOIN journal_voices v ON v.session=l.session AND v.voice=l.voice WHERE l.id<? "+(filter?"AND l.check_words<>'' ":"")+(needle.isEmpty()?"":"AND (instr(lower(l.text),lower(?))>0 OR instr(lower(l.original),lower(?))>0) ")+"ORDER BY l.id DESC LIMIT 41",needle.isEmpty()?new String[]{String.valueOf(before)}:new String[]{String.valueOf(before),needle,needle})){
                    int count=0;long last=0;DateFormat format=DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.MEDIUM);
                    while(c.moveToNext()){
                        if(count++==40){boundary=last;break;}last=c.getLong(0);
                        String who=JournalStore.voiceLabel(c.getInt(4),c.getString(5),c.getString(6));
                        int start=result.length();
                        result.append(format.format(new Date(c.getLong(1)))).append(who.isEmpty()?"":" · "+who)
                            .append(c.getInt(3)==1?" · unfinished":c.getInt(3)==2?" · capture status":"").append('\n').append(!compare&&c.getInt(3)==0?SpeechContext.reading(c.getString(2)):c.getString(2));
                        String uncertain=c.getString(8);long at=c.getLong(1);
                        if(!uncertain.isEmpty())result.append("\nCheck wording · ").append(uncertain);
                        if(compare&&!c.isNull(7))result.append("\nOriginally heard · ").append(c.getString(7));
                        if(c.getInt(3)!=2){
                            long row=c.getLong(0);String wording=c.getString(2),raw=c.isNull(7)?wording:c.getString(7);
                            result.setSpan(new ClickableSpan(){
                                public void onClick(android.view.View view){editLine(row,wording,raw,uncertain,at);}
                                @Override public void updateDrawState(android.text.TextPaint paint){paint.setColor(0xff16483c);}
                            },start,result.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        }
                        result.append("\n\n");
                    }
                }
            }catch(RuntimeException failure){error="Could not read the journal.";}
            final int total=needsReview;final long mark=boundary;if(result.length()>=2)result.delete(result.length()-2,result.length());
            final CharSequence body=error!=null?error:result;
            main.post(()->{if(!visible||request!=generation)return;
                if(filter && total>0 && body.length()==0 && pages.size()>1){
                    pages.clear();pages.add(Long.MAX_VALUE);read();return;
                }
                busy=false;next=mark;older.setEnabled(next>0);
                review.setVisibility(total>0 || filter?android.view.View.VISIBLE:android.view.View.GONE);
                review.setText(filter?"Show all journal lines":"Check uncertain wording · "+total);
                CharSequence value=body.length()==0 && !needle.isEmpty()?"No matching passages. Try a shorter phrase.":body.length()==0 && filter?"No passages left to check.":body.length()==0?(JournalStore.enabled(this)?"Journal saving is on. Recognized lines will appear here while listening.":"Journal saving is off. Turn it on above to keep future words.") :body;
                if(!android.text.TextUtils.equals(transcript.getText(),value))transcript.setText(value);
                if(restoreScroll>0){int y=restoreScroll;restoreScroll=0;journalScroll.post(()->journalScroll.scrollTo(0,y));}
            });
        });
    }
}
