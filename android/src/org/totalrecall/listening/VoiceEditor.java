package org.totalrecall.listening;

import android.app.*;
import android.text.InputFilter;
import android.widget.*;
import java.util.*;
import java.util.concurrent.Executor;

/** Edit the meaning of a suggested voice, or correct a single journal turn. */
final class VoiceEditor {
    private static final String[] SOURCES={"Unspecified","Me","Another person","Media"};
    private final Activity activity;
    private final Executor worker;
    private final long session,generation;
    private final Runnable changed;
    private final TreeMap<Integer,String[]> voices=new TreeMap<>();
    VoiceEditor(Activity a,Executor executor,long session,Runnable changed){
        activity=a;worker=executor;this.session=session;this.changed=changed;
        LiveCapture.Snapshot snapshot=LiveCapture.snapshot();generation=snapshot.voiceSession;
        if(session==snapshot.journalId)for(Map.Entry<Integer,String> v:snapshot.names.entrySet()){
            String[] parts=v.getValue().split(" · ",3);String name="",source="";
            for(int i=1;i<parts.length;i++){if(Arrays.asList(SOURCES).contains(parts[i]))source=parts[i];else name=parts[i];}
            voices.put(v.getKey(),new String[]{name,source});
        }
    }
    private void ui(Runnable r){activity.runOnUiThread(()->{if(!activity.isFinishing()&&!activity.isDestroyed())r.run();});}
    private void issue(Exception e){ui(()->Toast.makeText(activity,e.getMessage(),Toast.LENGTH_LONG).show());}
    void open(){
        if(session<0){menu();return;}
        worker.execute(()->{try(JournalStore db=new JournalStore(activity)){
            TreeMap<Integer,String[]> found=db.voices(session);ui(()->{voices.putAll(found);menu();});
        }catch(RuntimeException e){issue(e);}});
    }
    private String label(int id){String[] v=voices.get(id);return v==null?LiveCapture.defaultVoice(id):JournalStore.voiceLabel(id,v[0],v[1]);}
    private void menu(){
        ArrayList<Integer> ids=new ArrayList<>(voices.keySet());ArrayList<String> options=new ArrayList<>();
        for(int id:ids)options.add(label(id));
        if(session>=0)options.add("Correct a journal line…");
        AlertDialog.Builder dialog=new AlertDialog.Builder(activity).setTitle("Name voices & sources").setNegativeButton("Close",null);
        if(options.isEmpty())dialog.setMessage("A voice appears after a sufficiently long spoken phrase. Enable the text journal to keep labels and correct individual lines.");
        else dialog.setItems(options.toArray(new String[0]),(d,i)->{if(i<ids.size())edit(ids.get(i),-1);else turns(Long.MAX_VALUE);});
        dialog.show();
    }
    private void turns(long before){
        worker.execute(()->{try(JournalStore db=new JournalStore(activity)){
            List<JournalStore.Turn> rows=db.turns(session,before);
            ui(()->{
                ArrayList<String> labels=new ArrayList<>();int count=Math.min(30,rows.size());
                for(int i=0;i<count;i++){JournalStore.Turn t=rows.get(i);String who=label(t.voice);labels.add((who.isEmpty()?"Unassigned":who)+" · "+t.text.substring(0,Math.min(140,t.text.length())));}
                if(rows.size()>count)labels.add("Earlier lines…");
                AlertDialog.Builder dialog=new AlertDialog.Builder(activity).setTitle("Choose a line to attribute").setNegativeButton("Close",null);
                if(labels.isEmpty())dialog.setMessage("No saved words in this journal yet.");
                else dialog.setItems(labels.toArray(new String[0]),(d,i)->{if(i==count)turns(rows.get(count-1).id);else choose(rows.get(i).id);});
                dialog.show();
            });
        }catch(RuntimeException e){issue(e);}});
    }
    private void choose(long row){
        ArrayList<Integer> ids=new ArrayList<>(voices.keySet());ids.add(-1);
        ArrayList<String> options=new ArrayList<>();for(int id:ids)options.add(label(id));options.add("Add a named voice…");
        new AlertDialog.Builder(activity).setTitle("Who said this line?").setItems(options.toArray(new String[0]),(d,i)->{
            if(i==ids.size()){int next=voices.isEmpty()?13:Math.max(13,voices.lastKey()+1);edit(next,row);}
            else save(ids.get(i),null,null,row);
        }).setNegativeButton("Cancel",null).show();
    }
    private void edit(int id,long row){
        LinearLayout fields=new LinearLayout(activity);fields.setOrientation(LinearLayout.VERTICAL);
        int pad=Math.round(18*activity.getResources().getDisplayMetrics().density);fields.setPadding(pad,0,pad,0);
        EditText name=new EditText(activity);name.setSingleLine();name.setHint("Name or role (optional)");name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(64)});
        String[] current=voices.getOrDefault(id,new String[]{"",""});name.setText(current[0]);fields.addView(name);
        Spinner source=new Spinner(activity);source.setContentDescription("Voice source");source.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,SOURCES));
        source.setSelection(Math.max(0,Arrays.asList(SOURCES).indexOf(current[1])));fields.addView(source);
        new AlertDialog.Builder(activity).setTitle("Label Voice "+id).setMessage("This label applies within this session. Choose Media for a TV, podcast or other playback.")
            .setView(fields).setNegativeButton("Cancel",null).setPositiveButton("Save label",(d,w)->save(id,name.getText().toString().trim(),source.getSelectedItemPosition()==0?"":(String)source.getSelectedItem(),row)).show();
    }
    private void save(int id,String name,String source,long row){
        worker.execute(()->{try{
            if(name!=null && (name.contains("[")||name.contains("]")||name.contains(" · ")))throw new IllegalArgumentException("Choose a name without brackets or a middle dot.");
            if(session>=0)try(JournalStore db=new JournalStore(activity)){
                android.database.sqlite.SQLiteDatabase sql=db.getWritableDatabase();sql.beginTransaction();
                try{if(name!=null)db.nameVoice(session,id,name,source);if(row>=0)db.assign(session,row,id);sql.setTransactionSuccessful();}
                finally{sql.endTransaction();}
            }
            if(name!=null){
                LiveCapture.Snapshot current=LiveCapture.snapshot();
                boolean live=session==current.journalId && LiveCapture.rename(generation,id,JournalStore.voiceLabel(id,name,source));
                if(session<0&&!live)throw new IllegalStateException("That live session has ended.");
            }
            ui(()->{Toast.makeText(activity,"Voice label saved",Toast.LENGTH_SHORT).show();changed.run();});
        }catch(RuntimeException e){issue(e);}});
    }
}
