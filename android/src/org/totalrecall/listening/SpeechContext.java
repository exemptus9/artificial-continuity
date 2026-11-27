package org.totalrecall.listening;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.*;
import java.util.*;
import java.util.regex.*;
import org.json.*;

/** Local spelling choices. Each generic edit needs a matching phrase around the replaced word. */
final class SpeechContext {
    private static final Pattern LITERAL = Pattern.compile("\\b(?:spell(?:ed|ing)?|homophone|pun intended|rhymes? with|play on words|wordplay|the word|put in quotes|quote unquote)\\b",Pattern.CASE_INSENSITIVE);
    static final class Rule {
        final Pattern when;
        final String replacement;
        Rule(String spelling,String regex){replacement=spelling;when=Pattern.compile(regex,Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE);}
    }
    static final class Entry {
        final String heard,spelling;
        Entry(String heard,String spelling){this.heard=heard;this.spelling=spelling;}
    }
    private static List<Rule> generic;
    private final SharedPreferences prefs;
    private String stored=null;
    private List<Rule> custom=Collections.emptyList();
    SpeechContext(Context c) throws IOException {
        prefs=c.getSharedPreferences("recall-speech",0);
        synchronized(SpeechContext.class){
            if(generic==null){List<Rule> loaded=new ArrayList<>();
                try(BufferedReader reader=new BufferedReader(new InputStreamReader(c.getAssets().open("listening/speech-context.tsv"),"UTF-8"))){
                    for(String line;(line=reader.readLine())!=null;){String[] parts=line.split("\t",2);loaded.add(new Rule(parts[0],parts[1]));}
                }
                generic=Collections.unmodifiableList(loaded);
            }
        }
    }
    private static final Pattern QUESTION=Pattern.compile("^(?:(?:how|where|when|why|what) (?:do|does|did|is|are|can|could|will|would|should)|(?:can|could|would|will|did|do|should) (?:you|we|i))\\b",Pattern.CASE_INSENSITIVE);
    static String reading(String words){
        if(words.length()<4||words.indexOf(' ')<0||LITERAL.matcher(words).find())return words;
        String result=Character.toUpperCase(words.charAt(0))+words.substring(1);
        if(QUESTION.matcher(words).find() && Character.isLetterOrDigit(words.charAt(words.length()-1)))return result+"?";
        return result;
    }
    static String tail(String words){
        int start=words.length(),spaces=0;
        while(start>0 && words.length()-start<160){
            if(words.charAt(start-1)==' ' && ++spaces>=9)break;
            start--;
        }
        return words.substring(start);
    }
    static List<Entry> entries(SharedPreferences p){
        List<Entry> result=new ArrayList<>();
        try{JSONArray rows=new JSONArray(p.getString("spellings","[]"));
            for(int i=0;i<rows.length();i++){JSONArray row=rows.getJSONArray(i);result.add(new Entry(row.getString(0),row.getString(1)));}
        }catch(JSONException e){throw new IllegalStateException("Could not read saved spellings",e);}
        return result;
    }
    static void save(SharedPreferences p,List<Entry> entries){
        JSONArray rows=new JSONArray();
        for(Entry item:entries)rows.put(new JSONArray().put(item.heard).put(item.spelling));
        if(!p.edit().putString("spellings",rows.toString()).commit())throw new IllegalStateException("Could not save spellings");
    }
    static boolean valid(String s){return !s.trim().isEmpty() && s.length()<=80 && !Pattern.compile("\\p{Cntrl}").matcher(s).find();}
    String apply(String previous,String words){return apply(previous,words,null);}
    String apply(String previous,String words,VoiceProfiles.Profile speaker){
        if(words==null||words.isEmpty()||!prefs.getBoolean("contextSpelling",true))return words;
        String data=prefs.getString("spellings","[]");
        if(!data.equals(stored)){
            List<Entry> entries=entries(prefs);entries.sort((a,b)->Integer.compare(b.heard.length(),a.heard.length()));
            ArrayList<Rule> rules=new ArrayList<>();
            for(Entry e:entries)rules.add(new Rule(e.spelling,"(?<![\\p{L}\\p{N}'])("+Pattern.quote(e.heard)+")(?![\\p{L}\\p{N}'])"));
            custom=rules;stored=data;
        }
        if(speaker==null || !speaker.source.equals("Me") || speaker.trainedRules.isEmpty())return correct(previous,words,generic,custom);
        List<Rule> personal=new ArrayList<>(speaker.trainedRules);personal.addAll(custom);
        return correct(previous,words,generic,personal);
    }
    static String correct(String previous,String words,List<Rule> rules,List<Rule> personal){
        String prefix=previous.isEmpty()?"":previous+" ";
        String input=prefix+words;
        if(LITERAL.matcher(input).find())return words;
        // Collect non-overlapping edits against untouched input. A personal spelling wins
        // over a stock rule; inserting a word can never start a chain of substitutions.
        TreeMap<Integer,Edit> edits=new TreeMap<>();
        collect(input,prefix.length(),personal,edits);
        collect(input,prefix.length(),rules,edits);
        if(edits.isEmpty())return words;
        StringBuilder result=new StringBuilder(input);
        for(Map.Entry<Integer,Edit> item:edits.descendingMap().entrySet())result.replace(item.getKey(),item.getValue().end,item.getValue().value);
        return result.substring(prefix.length());
    }
    static final class Edit{final int end;final String value;Edit(int end,String value){this.end=end;this.value=value;}}
    private static void collect(String input,int boundary,List<Rule> rules,TreeMap<Integer,Edit> edits){
        for(Rule rule:rules){Matcher found=rule.when.matcher(input);
            while(found.find()){int start=found.start(1),end=found.end(1);if(start<boundary||found.group(1).equals(rule.replacement))continue;
                Map.Entry<Integer,Edit> older=edits.floorEntry(start),next=edits.ceilingEntry(start);
                if((older!=null&&older.getValue().end>start)||(next!=null&&next.getKey()<end))continue;
                edits.put(start,new Edit(end,rule.replacement));
            }
        }
    }
}
