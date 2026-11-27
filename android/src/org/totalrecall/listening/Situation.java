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
    private final Map<String,Integer> lastSeen=new HashMap<>();
    private boolean recent(String cue){return passages-lastSeen.getOrDefault(cue,-10)<=2;}
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
                Matcher found=rule.getValue().matcher(match);
                if(found.find()){lastSeen.put(rule.getKey(),passages);evidence.putIfAbsent(rule.getKey(),quote(line,stamp,found.start()));}
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
        if(recent("Media sign-off"))return "Possible media playback · a broadcast-style phrase was heard.";
        if(recent("Draft revision") && recent("Creative idea"))return "Possible spoken drafting · an idea and a revision were heard close together.";
        if(recent("Personal recollection") && recent("Sensory detail"))return "Possible storytelling · a personal memory and a sensory detail were heard close together.";
        if(obstacle && (step||question))return "Possible troubleshooting · an obstacle and a question or next step were mentioned.";
        if(idea && (decision||step))return "Possible creative planning · an idea and a choice or next step were mentioned.";
        if(decision && step)return "Possible planning · a choice and a follow-up were mentioned.";
        if(step && time)return "Possible scheduling · a next step and a time were mentioned.";
        if(question && evidence.containsKey("Needs checking"))return "Possible fact-checking · a question and uncertainty were expressed.";
        if(evidence.isEmpty())return "";
        return "Words suggest · "+cue();
    }
    static void updateLive(Context c,String words,boolean media){
        if(media){LiveCapture.noteSituation("Possible media playback");return;}
        try{
            Situation newest=new Situation(c);newest.add(words,"");
            LiveCapture.Snapshot snapshot=LiveCapture.snapshot();
            String label=newest.interpretation();
            if(!label.isEmpty() && !newest.recent("Media sign-off") && snapshot.finals.size()>1){
                int i=snapshot.finals.size()-1;
                // A brief neutral line can separate an idea from its revision. Stop at a known speaker change.
                Situation earlier=new Situation(c);
                int first=i, speaker=snapshot.voices.get(i);
                while(first>0 && i-first<2 &&
                      snapshot.recognizedAt.get(i)-snapshot.recognizedAt.get(first-1)<=18000){
                    int prior=snapshot.voices.get(first-1);
                    if(prior>0 && speaker>0 && prior!=speaker)break;
                    if(prior>0)speaker=prior;
                    first--;
                }
                for(int n=first;n<i;n++)earlier.add(snapshot.finals.get(n),"");
                String before=earlier.interpretation();
                earlier.add(words,"");
                String combined=earlier.interpretation();
                if(combined.startsWith("Possible ") && !combined.equals(before)
                    && !combined.startsWith("Possible media playback"))label=combined;
            }else if(label.isEmpty() && snapshot.finals.size()>=3){
                int i=snapshot.finals.size()-1;
                if(snapshot.recognizedAt.get(i)-snapshot.recognizedAt.get(i-2)<18000 &&
                    snapshot.voices.get(i)>0 && snapshot.voices.get(i-1)>0 &&
                    !snapshot.voices.get(i).equals(snapshot.voices.get(i-1)))label=snapshot.scene(snapshot.finalSequence-3);
            }
            if(label.startsWith("Words suggest · "))label=label.substring("Words suggest · ".length());
            int why=label.indexOf(" · ");if(why>0)label=label.substring(0,why);
            LiveCapture.noteSituation(label);
        }catch(IOException ignored){}
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
