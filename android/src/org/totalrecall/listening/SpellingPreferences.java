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
        TextView label = new TextView(a);
        label.setText(hint);
        body.addView(label);
        EditText field = new EditText(a);
        field.setSingleLine(true);
        field.setId(android.view.View.generateViewId());
        label.setLabelFor(field.getId());
        field.setHint(hint);
        field.setContentDescription(hint);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});
        field.setText(value);
        body.addView(field);
        return field;
    }
    static void fromCorrection(Activity activity, String original, EditText corrected) {
        try {
            VoiceWording change = VoiceWording.difference(original, corrected.getText().toString());
            if (change == null) {
                corrected.setError("Change the spelling in this passage first.");
                corrected.requestFocus();
                return;
            }
            edit(activity, activity.getSharedPreferences("recall-speech", 0), null,
                    change.heard, change.wanted, false);
        } catch (IllegalArgumentException tooLong) {
            edit(activity, activity.getSharedPreferences("recall-speech", 0), null, "", "", false);
        }
    }

    private static void edit(Activity a,SharedPreferences prefs,SpeechContext.Entry old){
        edit(a, prefs, old, old == null ? "" : old.heard, old == null ? "" : old.spelling, true);
    }

    private static void edit(Activity a, SharedPreferences prefs, SpeechContext.Entry old,
                             String heardText, String preferredText, boolean returnToList) {
        LinearLayout body=new LinearLayout(a);body.setOrientation(LinearLayout.VERTICAL);
        int pad=Math.round(20*a.getResources().getDisplayMetrics().density);body.setPadding(pad,0,pad,0);
        EditText from=field(a,body,"Words the recognizer writes",heardText);
        EditText to=field(a,body,"Your preferred spelling",preferredText);
        String message = returnToList ? "Enter the words you see and your preferred spelling."
                : "Check the short phrase to remember. You can still edit this journal passage.";
        message += prefs.getBoolean("contextSpelling", true)
                ? " Future matching phrases on this phone can use this spelling."
                : " Turn on context spelling in Capture choices to use it.";
        ScrollView scroll = new ScrollView(a);
        scroll.addView(body);
        AlertDialog dialog=new AlertDialog.Builder(a).setTitle("Teach a spelling").setMessage(message)
            .setView(scroll).setPositiveButton(returnToList ? "Save" : "Save spelling",null).setNegativeButton("Cancel",null).create();
        dialog.show();dialog.getButton(-1).setOnClickListener(v->{
            String heard=from.getText().toString().trim().replaceAll("\\s+"," "),spelling=to.getText().toString().trim();
            if(!SpeechContext.valid(heard)||!SpeechContext.valid(spelling)||heard.equals(spelling)){from.setError("Enter two different, nonempty spellings.");return;}
            List<SpeechContext.Entry> all=SpeechContext.entries(prefs);
            if(old!=null)all.removeIf(e->e.heard.equalsIgnoreCase(old.heard));
            SpeechContext.Entry existing = null;
            for (SpeechContext.Entry entry : all) {
                if (entry.heard.equalsIgnoreCase(heard)) existing = entry;
            }
            all.removeIf(e->e.heard.equalsIgnoreCase(heard));
            if(all.size()>=64){from.setError("Remove a spelling before adding another.");return;}
            all.add(new SpeechContext.Entry(heard,spelling));
            Runnable save = () -> {
                try {
                    SpeechContext.save(prefs, all);
                } catch (IllegalStateException failure) {
                    from.setError("Could not save this spelling. Try again.");
                    return;
                }
                dialog.dismiss();
                if (returnToList) open(a);
                else Toast.makeText(a, "Spelling saved on this phone", Toast.LENGTH_SHORT).show();
            };
            if (existing != null && !existing.spelling.equals(spelling)) {
                new AlertDialog.Builder(a).setTitle("Replace this spelling?")
                        .setMessage(heard + " currently uses “" + existing.spelling + "”. Use “" + spelling + "” instead?")
                        .setPositiveButton("Replace", (d, which) -> save.run())
                        .setNegativeButton("Go back", null).show();
            } else save.run();
        });
    }
}
