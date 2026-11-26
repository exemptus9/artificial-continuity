package org.totalrecall.listening;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/** Extractive, local context. Each interpretation keeps the words that triggered it. */
public final class Situation {
    private static volatile Map<String,Pattern> rules;
    private final Map<String,Pattern> cues;
    private final Map<String,String> evidence=new LinkedHashMap<>();
    private String first="";
    public String cue(){return evidence.isEmpty()?"":evidence.keySet().iterator().next();}
    private int passages;
    public Situation(Context c) throws IOException {
        synchronized(Situation.class){
            if(rules==null){
                Map<String,Pattern> loaded=new LinkedHashMap<>();
                try(BufferedReader in=new BufferedReader(new InputStreamReader(c.getAssets().open("listening/situation-rules.tsv"),StandardCharsets.UTF_8))){
                    for(String row;(row=in.readLine())!=null;){String[] pair=row.split("\\t",2);loaded.put(pair[0],Pattern.compile(pair[1],Pattern.CASE_INSENSITIVE));}
                }
                rules=loaded;
            }
        }
        cues=rules;
    }
    Situation(Map<String,Pattern> tests){cues=tests;}
    public void add(String words,String stamp){
        if(words==null || words.trim().isEmpty())return;
        for(String raw:words.split("(?<=[.!?])\\s+|\\n+")){
            String line=raw.trim();if(line.isEmpty())continue;
            passages++;
            if(first.isEmpty())first=quote(line,stamp,0);
            String match=line.replace('’','\'');
            for(Map.Entry<String,Pattern> rule:cues.entrySet()){
                if(evidence.containsKey(rule.getKey()))continue;
                Matcher found=rule.getValue().matcher(match);
                if(found.find()){evidence.put(rule.getKey(),quote(line,stamp,found.start()));}
            }
        }
    }
    private static String quote(String line,String stamp,int at){
        int start=Math.max(0,at-55),end=Math.min(line.length(),start+190);
        return (stamp.isEmpty()?"":stamp+"  ")+(start>0?"…":"")+"“"+line.substring(start,end)+(end<line.length()?"…":"")+"”";
    }
    public String draft(){
        if(first.isEmpty())return "";
        if(evidence.isEmpty())return "One passage reads "+first+".";
        java.util.LinkedHashSet<String> seen=new java.util.LinkedHashSet<>();
        ArrayList<String> lines=new ArrayList<>();
        for(Map.Entry<String,String> e:evidence.entrySet()){
            if(!seen.add(e.getValue()))continue;
            lines.add(e.getKey()+" in the source words — "+e.getValue()+".");
            if(lines.size()==4)break;
        }
        return String.join(" ",lines);
    }
    public String result(){
        if(passages==0)return "No words available to interpret.";
        if(evidence.isEmpty())return "Source excerpt  "+first+"\nNo explicit situation cue matched.";
        ArrayList<String> lines=new ArrayList<>();
        for(Map.Entry<String,String> e:evidence.entrySet())lines.add(e.getKey()+"  "+e.getValue());
        return String.join("\n\n",lines);
    }
    public static String read(Context c,String text,boolean journal){
        return read(c,text,journal,false);
    }
    static String read(Context c,String text,boolean journal,boolean story){
        try{
            Situation result=new Situation(c);
            if(journal){
                Pattern entry=Pattern.compile("^\\d{4}-\\d{2}-\\d{2} (\\d{2}:\\d{2}:\\d{2}) (?:Z|[+-]\\d{2}:\\d{2})  (.+)$");
                for(String line:text.split("\n")){
                    Matcher m=entry.matcher(line);
                    if(m.matches()&&!m.group(2).startsWith("[capture status]"))result.add(m.group(2),m.group(1));
                }
            }else result.add(text,"");
            return story?result.draft():result.result();
        }catch(IOException e){return "Situation cues could not be loaded.";}
    }
}
