package org.totalrecall.listening;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import org.json.*;
import java.util.*;

/** Confirmed examples are stored only in this installation's private, backup-disabled data. */
public final class VoiceProfiles {
    private static final String FILE="remembered-voices-v1";
    private static final String MODEL="vosk-spk-0.4";
    static final int MAX_SAMPLES=8;
    static final class Profile {
        final String id,name,source;
        final List<double[]> examples=new ArrayList<>();
        final LinkedHashMap<String,String> wordings=new LinkedHashMap<>();
        final List<SpeechContext.Rule> trainedRules=new ArrayList<>();
        void compileWordings(){
            trainedRules.clear();ArrayList<String> heard=new ArrayList<>(wordings.keySet());
            heard.sort((a,b)->Integer.compare(b.length(),a.length()));
            for(String key:heard)trainedRules.add(new VoiceWording(key,wordings.get(key)).rule());
        }
        Profile(String id,String name,String source){this.id=id;this.name=name;this.source=source;}
        String description(){return name+" · "+source;}
    }
    static final class Match {
        final Profile profile;final boolean uncertain;
        Match(Profile p,boolean uncertain){profile=p;this.uncertain=uncertain;}
    }
    private final SharedPreferences prefs;
    private String cached=null;
    private List<Profile> profiles=Collections.emptyList();
    VoiceProfiles(Context c){prefs=c.getApplicationContext().getSharedPreferences(FILE,Context.MODE_PRIVATE);}
    List<Profile> list(){
        String data=prefs.getString(MODEL,"[]");
        if(data.equals(cached))return profiles;
        List<Profile> result=new ArrayList<>();
        try{
            JSONArray rows=new JSONArray(data);
            for(int i=0;i<rows.length();i++){
                JSONObject row=rows.getJSONObject(i);Profile p=new Profile(row.getString("id"),row.getString("name"),row.getString("source"));
                JSONObject learned=row.optJSONObject("wordings");
                if(learned!=null)for(Iterator<String> keys=learned.keys();keys.hasNext();){String k=keys.next();p.wordings.put(k,learned.getString(k));}
                p.compileWordings();JSONArray samples=row.getJSONArray("samples");
                for(int j=0;j<samples.length();j++){double[] v=vector(samples.optJSONArray(j));if(v!=null)p.examples.add(v);}
                if(!p.examples.isEmpty())result.add(p);
            }
        }catch(JSONException invalid){throw new IllegalStateException("Could not read the saved voice profiles.",invalid);}
        profiles=result;cached=data;return profiles;
    }
    static double[] vector(JSONArray raw){
        if(raw==null||raw.length()!=128)return null;
        double[] v=new double[128];double norm=0;
        for(int i=0;i<v.length;i++){v[i]=raw.optDouble(i,Double.NaN);norm+=v[i]*v[i];}
        if(!(norm>0)||!Double.isFinite(norm))return null;
        norm=Math.sqrt(norm);for(int i=0;i<v.length;i++)v[i]/=norm;return v;
    }
    private static double similarity(double[] a,double[] b){
        double dot=0;for(int i=0;i<a.length;i++)dot+=a[i]*b[i];return dot;
    }
    static Match identify(List<Profile> profiles,double[] v){
        Profile best=null;double first=-2,second=-2;
        for(Profile p:profiles){
            double top=-2,runner=-2;
            for(double[] e:p.examples){double score=similarity(e,v);if(score>top){runner=top;top=score;}else runner=Math.max(runner,score);}
            double score=p.examples.size()<2?top:.80*top+.20*runner;
            if(score>first){second=first;first=score;best=p;}else second=Math.max(second,score);
        }
        if(best!=null && first>=(best.examples.size()==1?.76:.68) && first-second>=.09)return new Match(best,false);
        return new Match(null,first>=.52);
    }
    /** Preserve the first enrollment and scarce delivery examples when the eight slots are full. */
    static void retainExample(List<double[]> bank,double[] sample){
        for(double[] v:bank)if(similarity(v,sample)>.995)return;
        bank.add(sample.clone());
        if(bank.size()<=MAX_SAMPLES)return;
        int redundant=1;
        double best=Double.NEGATIVE_INFINITY,secondBest=Double.NEGATIVE_INFINITY;
        // Every endpoint of the closest pair has the same nearest score. Their next-nearest
        // neighbor tells us which endpoint is better covered by the rest of the bank.
        for(int i=1;i<bank.size();i++){
            double first=-2,second=-2;
            for(int j=0;j<bank.size();j++)if(j!=i){
                double score=similarity(bank.get(i),bank.get(j));
                if(score>=first){second=first;first=score;}else if(score>second)second=score;
            }
            if(first>best || first==best && second>=secondBest){
                best=first;secondBest=second;redundant=i;
            }
        }
        bank.remove(redundant);
    }
    private void write(List<Profile> updated){
        JSONArray rows=new JSONArray();
        try{for(Profile p:updated){
            JSONArray samples=new JSONArray();for(double[] v:p.examples){JSONArray a=new JSONArray();for(double n:v)a.put(n);samples.put(a);}
            rows.put(new JSONObject().put("id",p.id).put("name",p.name).put("source",p.source).put("samples",samples).put("wordings",new JSONObject(p.wordings)));
        }}catch(JSONException ex){throw new IllegalStateException("Could not encode voice samples.",ex);}
        if(!prefs.edit().putString(MODEL,rows.toString()).commit())throw new IllegalStateException("Could not save voice profiles.");
        cached=null;
    }
    static void checkName(String name){
        if(name.isEmpty()||name.length()>64||name.matches("(?s).*[\\[\\]\\p{Cntrl}].*")||name.contains(" · "))throw new IllegalArgumentException("Enter a short name without brackets or line breaks.");
    }
    void rename(String id,String name,String source){
        synchronized(VoiceProfiles.class){
            checkName(name);List<Profile> all=new ArrayList<>(list());
            Profile old=null;for(Profile p:all){
                if(p.id.equals(id))old=p;
                else if(p.source.equals(source) && (source.equals("Me") || p.name.equalsIgnoreCase(name)))throw new IllegalArgumentException("A profile already has that name or is marked Me.");
            }
            if(old==null)throw new IllegalArgumentException("This profile was removed.");
            Profile next=new Profile(id,name,source);next.examples.addAll(old.examples);if(source.equals("Me") && old.source.equals("Me"))next.wordings.putAll(old.wordings);all.remove(old);all.add(next);write(all);
        }
    }
    void enroll(String id,String name,String source,double[] sample){enroll(id,name,source,sample,null);}
    void enroll(String id,String name,String source,double[] sample,VoiceWording wording){
        synchronized(VoiceProfiles.class){
            name=name.trim();
            checkName(name);
            if(!Arrays.asList("Me","Another person","Media").contains(source))throw new IllegalArgumentException("Choose a voice source.");
            if(sample==null||sample.length!=128)throw new IllegalArgumentException("Listen to another clear phrase.");
            List<Profile> all=new ArrayList<>(list());
            Profile existing=null;
            for(Profile p:all){
                if(p.id.equals(id) || (id==null && source.equals("Me") && p.source.equals("Me")))existing=p;
                else if(p.name.equalsIgnoreCase(name) && p.source.equals(source))throw new IllegalArgumentException("Choose the existing profile to add an example.");
            }
            if(id!=null&&existing==null)throw new IllegalArgumentException("This profile was removed.");
            if(existing==null&&all.size()>=32)throw new IllegalArgumentException("Remove a profile before adding another.");
            Match recognized=identify(all,sample);
            if(recognized.profile!=null && recognized.profile!=existing)throw new IllegalArgumentException("This sample closely matches "+recognized.profile.name+". Try again with one person speaking.");
            Profile next=new Profile(existing!=null?existing.id:UUID.randomUUID().toString(),name,source);
            if(existing!=null){
                double best=-2;for(double[] v:existing.examples)best=Math.max(best,similarity(v,sample));
                if(best<.47)throw new IllegalArgumentException("This voice differs from your earlier samples. Try a clear phrase nearer the microphone.");
                next.examples.addAll(existing.examples);next.wordings.putAll(existing.wordings);all.remove(existing);
            }
            if(wording!=null && source.equals("Me")){
                String key=wording.heard.toLowerCase(Locale.ROOT);
                if(!next.wordings.containsKey(key) && next.wordings.size()>=48)throw new IllegalArgumentException("Forget an older voice correction before adding another.");
                next.wordings.put(key,wording.wanted);
            }
            retainExample(next.examples,sample);all.add(next);write(all);
        }
    }
    void forgetWording(String id,String heard){
        synchronized(VoiceProfiles.class){
            List<Profile> all=new ArrayList<>(list());
            for(int i=0;i<all.size();i++){Profile old=all.get(i);if(!old.id.equals(id))continue;
                Profile next=new Profile(old.id,old.name,old.source);next.examples.addAll(old.examples);next.wordings.putAll(old.wordings);next.wordings.remove(heard);all.set(i,next);write(all);return;
            }
        }
    }
    void forget(String id){synchronized(VoiceProfiles.class){List<Profile> all=new ArrayList<>(list());all.removeIf(p->p.id.equals(id));write(all);}}
    public static void reset(Context c){synchronized(VoiceProfiles.class){if(!c.getSharedPreferences(FILE,0).edit().clear().commit())throw new IllegalStateException("Could not erase voice profiles.");cancel();}}
    static final class Example{
        final String text;final double[] voice;
        Example(String t,double[] v){text=t;voice=v.clone();}
    }
    private static long opened=-1;
    private static Example example;
    private static String attempt="";
    static synchronized String attempt(){return attempt;}
    static synchronized void listen(){opened=SystemClock.elapsedRealtime();example=null;attempt="";}
    static synchronized void cancel(){opened=-1;example=null;attempt="";}
    static synchronized void offer(String words,double[] v,int frames){
        long age=SystemClock.elapsedRealtime()-opened;
        if(opened<0 || age>90000 || example!=null || age+200<frames*10L)return;
        int count=words.trim().isEmpty()?0:words.trim().split("\\s+").length;
        if(count<3){attempt="Heard "+count+(count==1?" word":" words")+" · say a whole sentence, then pause.";return;}
        if(frames<150 || v==null){
            attempt="Heard “"+words+"” · too little clear speech to compare voices. Try one longer phrase.";
            return;
        }
        attempt="";example=new Example(words,v);
    }
    static synchronized Example sample(){return example;}
}
