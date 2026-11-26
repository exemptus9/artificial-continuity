package org.totalrecall.listening;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import android.text.InputFilter;
import android.widget.*;

/** The story belongs to its item's private catalog and shares its deletion lifetime. */
final class StoryNotes {
    enum Kind { CLIP("clips"), JOURNAL("sessions"), SEGMENT("segments");
        final String table;Kind(String table){this.table=table;}
        SQLiteOpenHelper open(Context c){
            switch(this){case CLIP:return new Store(c);case JOURNAL:return new JournalStore(c);default:return new ArchiveStore(c);}
        }
    }
    static String note(Context c,Kind kind,String id,String body,String expected){
        try(SQLiteOpenHelper helper=kind.open(c)){
            SQLiteDatabase db=helper.getWritableDatabase();db.beginTransaction();
            try{
                db.execSQL("CREATE TABLE IF NOT EXISTS story_notes(id TEXT PRIMARY KEY,body TEXT NOT NULL)");
                db.execSQL("CREATE TRIGGER IF NOT EXISTS remove_story AFTER DELETE ON "+kind.table+" BEGIN DELETE FROM story_notes WHERE id=CAST(OLD.id AS TEXT); END");
                try(Cursor owner=db.rawQuery("SELECT 1 FROM "+kind.table+" WHERE id=?",new String[]{id})){
                    if(!owner.moveToFirst())throw new IllegalStateException("The original item is no longer on this phone.");
                }
                String current="";
                try(Cursor row=db.rawQuery("SELECT body FROM story_notes WHERE id=?",new String[]{id})){if(row.moveToFirst())current=row.getString(0);}
                if(body!=null){
                    if(!current.equals(expected))throw new IllegalStateException("The story changed. Reopen it before editing.");
                    if(body.trim().isEmpty())db.delete("story_notes","id=?",new String[]{id});
                    else db.execSQL("INSERT OR REPLACE INTO story_notes VALUES(?,?)",new Object[]{id,body.trim()});
                }
                db.setTransactionSuccessful();return current;
            }finally{db.endTransaction();}
        }
    }
    static Button button(Activity a,Kind kind,String id,String draft){
        Button open=new Button(a);open.setAllCaps(false);open.setText("Recording story");
        open.setOnClickListener(v->{
            try{
                String saved=note(a,kind,id,null,null);
                EditText edit=new EditText(a);edit.setMinLines(3);edit.setMaxLines(10);edit.setGravity(android.view.Gravity.TOP);
                edit.setHint("What was happening?");edit.setFilters(new InputFilter[]{new InputFilter.LengthFilter(12000)});
                edit.setText(saved.isEmpty()?draft:saved);
                new AlertDialog.Builder(a).setTitle("Recording story").setMessage("Saved on this phone. You can edit or copy the suggested account.")
                    .setView(edit).setNegativeButton("Cancel",null).setPositiveButton("Save story",(d,w)->{
                        try{note(a,kind,id,edit.getText().toString(),saved);Toast.makeText(a,"Story saved",Toast.LENGTH_SHORT).show();}
                        catch(RuntimeException e){Toast.makeText(a,e.getMessage(),Toast.LENGTH_LONG).show();}
                    }).show();
            }catch(RuntimeException e){Toast.makeText(a,e.getMessage(),Toast.LENGTH_LONG).show();}
        });return open;
    }
}
