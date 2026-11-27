package org.totalrecall.listening;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.text.InputFilter;
import android.widget.*;
import java.util.*;

final class SpellingPreferences {
    static void open(Activity a){
        SharedPreferences prefs=a.getSharedPreferences("recall-speech",0);
        List<SpeechContext.Entry> entries=SpeechContext.entries(prefs);
        List<String> labels=new ArrayList<>();labels.add("Add a spelling");
        for(SpeechContext.Entry entry:entries)labels.add(entry.heard+" → "+entry.spelling);
        new AlertDialog.Builder(a).setTitle("My words & phrases").setItems(labels.toArray(new String[0]),(d,i)->{
            if(i==0){edit(a,prefs,null);return;}
            SpeechContext.Entry entry=entries.get(i-1);
            new AlertDialog.Builder(a).setTitle(entry.spelling).setMessage("Heard as “"+entry.heard+"”")
                .setPositiveButton("Edit",(dialog,which)->edit(a,prefs,entry))
                .setNeutralButton("Forget",(dialog,which)->{
                    List<SpeechContext.Entry> all=SpeechContext.entries(prefs);all.removeIf(e->e.heard.equalsIgnoreCase(entry.heard));SpeechContext.save(prefs,all);open(a);
                }).setNegativeButton("Close",null).show();
        }).setNegativeButton("Close",null).show();
    }
    private static EditText field(Activity a,LinearLayout body,String hint,String value){
        EditText field=new EditText(a);field.setSingleLine(true);field.setHint(hint);field.setContentDescription(hint);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});field.setText(value);body.addView(field);return field;
    }
    private static void edit(Activity a,SharedPreferences prefs,SpeechContext.Entry old){
        LinearLayout body=new LinearLayout(a);body.setOrientation(LinearLayout.VERTICAL);
        int pad=Math.round(20*a.getResources().getDisplayMetrics().density);body.setPadding(pad,0,pad,0);
        EditText from=field(a,body,"Words the recognizer writes",old==null?"":old.heard);
        EditText to=field(a,body,"Your preferred spelling",old==null?"":old.spelling);
        AlertDialog dialog=new AlertDialog.Builder(a).setTitle("Teach a spelling").setMessage("Enter the words you usually see and how you want them written. This applies on this phone.")
            .setView(body).setPositiveButton("Save",null).setNegativeButton("Cancel",null).create();
        dialog.show();dialog.getButton(-1).setOnClickListener(v->{
            String heard=from.getText().toString().trim().replaceAll("\\s+"," "),spelling=to.getText().toString().trim();
            if(!SpeechContext.valid(heard)||!SpeechContext.valid(spelling)||heard.equals(spelling)){from.setError("Enter two different, nonempty spellings.");return;}
            List<SpeechContext.Entry> all=SpeechContext.entries(prefs);
            if(old!=null)all.removeIf(e->e.heard.equalsIgnoreCase(old.heard));
            all.removeIf(e->e.heard.equalsIgnoreCase(heard));
            if(all.size()>=64){from.setError("Remove a spelling before adding another.");return;}
            all.add(new SpeechContext.Entry(heard,spelling));SpeechContext.save(prefs,all);dialog.dismiss();open(a);
        });
    }
}
