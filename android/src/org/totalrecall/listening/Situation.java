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
    String interpretation(){
        boolean decision=evidence.containsKey("Possible decision"),step=evidence.containsKey("Possible next step");
        boolean question=evidence.containsKey("Question"),obstacle=evidence.containsKey("Possible obstacle");
        boolean idea=evidence.containsKey("Creative idea"),time=evidence.containsKey("Time reference");
        if(obstacle && (step||question))return "Possible troubleshooting · an obstacle and a question or next step were mentioned.";
        if(idea && (decision||step))return "Possible creative planning · an idea and a choice or next step were mentioned.";
        if(decision && step)return "Possible planning · a choice and a follow-up were mentioned.";
        if(step && time)return "Possible scheduling · a next step and a time were mentioned.";
        if(question && evidence.containsKey("Needs checking"))return "Possible fact-checking · a question and uncertainty were expressed.";
        if(evidence.isEmpty())return "";
        return "Words suggest · "+cue();
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
        return interpretation()+"\n\n"+String.join("\n\n",lines);
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
                    if(m.matches()&&!m.group(2).startsWith("[capture status]")){
                        String words=m.group(2),source=m.group(1);int end=words.indexOf("] ");
                        if(end>0 && (words.startsWith("[Voice ")||words.startsWith("[Uncertain voice]"))){
                            source+=" · "+words.substring(1,end);words=words.substring(end+2);
                        }
                        result.add(words,source);
                    }
                }
            }else result.add(text,"");
            return story?result.draft():result.result();
        }catch(IOException e){return "Situation cues could not be loaded.";}
    }
}
