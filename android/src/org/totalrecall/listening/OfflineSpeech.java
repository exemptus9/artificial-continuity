package org.totalrecall.listening;

import android.content.Context;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONArray;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Decodes the same 16 kHz mono PCM that enters the volatile audio window. Single worker thread. */
public final class OfflineSpeech implements AutoCloseable {
    private static final String MODEL = "vosk-model-small-en-us-0.15";
    private final SpeechContext spellings;
    private String rawWords="",rawPartial="",priorContext="";
    private long priorAt,streamSamples;
    private int priorSpeaker;
    String rawWords(){return rawWords;}
    String rawPartial(){return rawPartial;}
    private String displayFinal(String json){
        String reading=verified(json);
        if(reading==null)return null;
        boolean same=voice>0 && voice==priorSpeaker && streamSamples-priorAt<5L*16000;
        String displayed=spellings.apply(same?priorContext:"",reading,known);
        priorContext=SpeechContext.tail(reading);priorAt=streamSamples;priorSpeaker=voice;
        rawPartial="";return displayed;
    }
    private final RoomNoise room=new RoomNoise();
    private final MicSignal micSignal=new MicSignal();
    private long nextReading;
    boolean extendedReach;
    boolean faintActivity(){return micSignal.audible();}
    void learnRoom(){room.learn();micSignal.learn();LiveCapture.roomStatus="Sampling room noise";LiveCapture.feedback("Learning room noise · stay quiet for two seconds");}
    void clearRoom(){room.reset();micSignal.reset();quietSpeech.reset();nextReading=0;LiveCapture.roomStatus="Room filter off";LiveCapture.micSignal="";}
    private final QuietSpeech quietSpeech=new QuietSpeech();
    private Model model;
    private org.vosk.SpeakerModel speakerModel;
    private final SpeakerGroups groups=new SpeakerGroups();
    private final VoiceProfiles profiles;
    private final java.util.Map<String,Integer> ids=new java.util.HashMap<>();
    private VoiceProfiles.Profile known;
    private double[] latestVector;
    private int frames;
    String attributedVoice(){return known==null?LiveCapture.defaultVoice(voice):known.description();}
    VoiceProfiles.Profile known(){return known;}
    void offerExample(){VoiceProfiles.offer(rawWords,latestVector,frames);}
    private int voice;
    private boolean archiveReview;
    private Recognizer retry;
    private final QuietSpeech reviewGain=new QuietSpeech();
    private short[] passage;
    private int used;
    private boolean overflow;
    private long energy;
    int compared, agreed, uncertain;
    private double confidence;
    private boolean weakWord;
    private String checkWords="",reviewHint="";
    String takeReviewHint(){String hint=reviewHint;reviewHint="";return hint;}
    String checkWords(){return checkWords;}
    private static double confidence(JSONObject json){
        JSONArray a=json.optJSONArray("result");
        if(a==null||a.length()==0)return 0;
        double sum=0;
        for(int i=0;i<a.length();i++){JSONObject word=a.optJSONObject(i);if(word!=null)sum+=word.optDouble("conf",0);}
        return sum/a.length();
    }
    boolean hasMediaProfiles(){for(VoiceProfiles.Profile p:profiles.list())if(p.source.equals("Media"))return true;return false;}
    boolean isMedia(){return known!=null && known.source.equals("Media");}
    private void rememberPcm(short[] pcm,int n){
        if(overflow)return;
        if(used==0){
            int peak=0;for(int i=0;i<n;i++)peak=Math.max(peak,Math.abs((int)pcm[i]));
            if(peak<16)return;
        }
        if(used+n>passage.length){overflow=true;return;}
        System.arraycopy(pcm,0,passage,used,n);used+=n;
        for(int i=0;i<n;i++)energy+=(long)pcm[i]*pcm[i];
    }
    private String verified(String json){
        String original=finalized(json);rawWords=original==null?"":original;
        reviewHint="";
        if(!archiveReview)return original;
        try{
            long meanSquare=energy/Math.max(1,used);
            boolean faint=meanSquare<400L*400L;
            if(overflow || used<8000 || (original==null && meanSquare<49) ||
                    (original!=null && confidence>=.83 && !weakWord && !faint))return original;
            compared++;
            if(retry==null){retry=new Recognizer(model,16000f);retry.setWords(true);}
            String[] alternatives=new String[2];double[] scores=new double[2];
            JSONArray[] detail={new JSONArray(),new JSONArray()};
            for(int pass=0;pass<2;pass++){
                retry.reset();reviewGain.reset();
                StringBuilder text=new StringBuilder();double total=0;int words=0;
                double prev=0,filtered=0;
                for(int off=0;off<used;off+=1600){
                    int n=Math.min(1600,used-off);
                    short[] input=java.util.Arrays.copyOfRange(passage,off,off+n);
                    if(pass==1)for(int i=0;i<n;i++){
                        double current=input[i];filtered=.965*(filtered+current-prev);prev=current;
                        input[i]=(short)Math.round(Math.max(-32768,Math.min(32767,filtered)));
                    }
                    short[] boosted=reviewGain.apply(input,n);
                    if(retry.acceptWaveForm(boosted,n)){
                        JSONObject part=new JSONObject(retry.getResult());int count=appendRetry(text,part,detail[pass]);
                        total+=count*confidence(part);words+=count;
                    }
                }
                JSONObject part=new JSONObject(retry.getFinalResult());int count=appendRetry(text,part,detail[pass]);
                alternatives[pass]=text.toString().trim();
                scores[pass]=(total+count*confidence(part))/Math.max(1,words+count);
            }
            JSONArray first=new JSONObject(json).optJSONArray("result");
            if(!alternatives[0].isEmpty() && spellings.apply("",alternatives[0]).equals(spellings.apply("",alternatives[1])) &&
                Math.min(scores[0],scores[1])>=Math.max(.78,(weakWord||faint)?Math.min(.90,confidence):confidence) &&
                RecognitionReview.supportedRewrite(original,first,alternatives[0],detail[0]) &&
                RecognitionReview.supportedRewrite(original,first,alternatives[1],detail[1])){
                agreed++;return alternatives[0];
            }
            String repaired=repairWeakWord(original,first,detail[0],detail[1]);
            if(repaired!=null){agreed++;return repaired;}
            if(!alternatives[0].isEmpty() && alternatives[0].equals(alternatives[1]) && !alternatives[0].equals(original))
                reviewHint="Other reading to check · "+alternatives[0].substring(0,Math.min(200,alternatives[0].length()));
            else if(!checkWords.isEmpty())reviewHint="Check wording · "+checkWords;
            uncertain++;return original;
        }catch(IOException|JSONException|RuntimeException failed){uncertain++;return original;}
        finally{used=0;overflow=false;energy=0;}
    }
    static String repairWeakWord(String original,JSONArray first,JSONArray a,JSONArray b){
        return RecognitionReview.repair(original,first,a,b);
    }
    private static int appendRetry(StringBuilder text,JSONObject part,JSONArray detail){
        JSONArray result=part.optJSONArray("result");
        if(result!=null)for(int i=0;i<result.length();i++)detail.put(result.optJSONObject(i));
        String words=part.optString("text","").trim();
        if(words.isEmpty())return 0;
        text.append(words).append(' ');return words.split(" +").length;
    }
    int lastVoice(){return voice;}
    boolean groupsReady(){return speakerModel!=null;}
    private Recognizer recognizer;
    private Recognizer commandRecognizer;
    private String pendingCommand;
    boolean filterCommands;
    private boolean commandFilterActive;
    private CommandNoise commandNoise;
    private final SelectiveEngine.CommandEndpoints commandEndpoints=new SelectiveEngine.CommandEndpoints();
    public OfflineSpeech(Context context) throws IOException {
        this(context,true);
    }
    OfflineSpeech(Context context,boolean commands) throws IOException {
        this(context,commands,commands && context.getSharedPreferences("recall-speech",0).getBoolean("voices",true));
    }
    OfflineSpeech(Context context,boolean commands,boolean groupVoices) throws IOException {
        spellings=new SpeechContext(context);profiles=new VoiceProfiles(context);
        File dir = unpack(context.getApplicationContext(),MODEL);
        try {
            model = new Model(dir.getAbsolutePath());
            recognizer = new Recognizer(model, 16000f);
            archiveReview=!commands;
            recognizer.setWords(true);
            if(archiveReview)passage=new short[16*16000];
            if(groupVoices)try{
                File path=unpack(context.getApplicationContext(),"vosk-model-spk-0.4");
                speakerModel=new org.vosk.SpeakerModel(path.getAbsolutePath());recognizer.setSpeakerModel(speakerModel);
            }catch(IOException|RuntimeException|LinkageError failed){
                if(speakerModel!=null){speakerModel.close();speakerModel=null;}Log.w("TotalRecallSpeech","Speaker grouping unavailable",failed);
            }
            if(!commands)return;
            org.json.JSONArray grammar = new org.json.JSONArray();
            for(String wake:SelectiveEngine.WAKES){
                grammar.put(wake);
                for(String[] group:SelectiveEngine.COMMANDS)for(int i=1;i<group.length;i++)grammar.put(wake+" "+group[i]);
            }
            for(String[] group:SelectiveEngine.COMMANDS)for(int i=1;i<group.length;i++)grammar.put(group[i]);
            for(String label:SelectiveEngine.CHOICE_WORDS)grammar.put(label);
            grammar.put("[unk]");
            commandRecognizer = new Recognizer(model, 16000f, grammar.toString());
        } catch (IOException | RuntimeException | LinkageError e) {
            close();
            throw new IOException("Offline speech could not start", e);
        }
    }

    /** Returns the finalized utterance on an endpoint and null on other frames. */
    public String accept(short[] pcm, int count) {
        return accept(pcm,count,false);
    }
    String accept(short[] pcm,int count,boolean liftQuiet) {return accept(pcm,count,liftQuiet,false);}
    String accept(short[] pcm,int count,boolean liftQuiet,boolean reduceRoom) {
        if(!liftQuiet)quietSpeech.reset();
        boolean learned=room.ready();
        if(!reduceRoom && (room.running() || micSignal.rejectedRoom()))clearRoom();
        if(!archiveReview)micSignal.observe(pcm,count);
        short[] filtered=reduceRoom?room.apply(pcm,count):pcm;
        if(!learned && room.ready()){
            if(micSignal.rejectedRoom()){
                room.reset();LiveCapture.roomStatus="Room sample varied · try again";
                LiveCapture.feedback("Room changed during sampling · retry with two quiet seconds");
            }else{
                LiveCapture.roomStatus="Room filter on";LiveCapture.feedback("Room noise learned · filtering live speech");
            }
        }
        short[] input=liftQuiet?quietSpeech.apply(filtered,count,archiveReview?12:micSignal.gainLimit(extendedReach)):filtered;
        if(!archiveReview && streamSamples>=nextReading){
            LiveCapture.micSignal=micSignal.reading(quietSpeech.gain(),liftQuiet);
            nextReading=streamSamples+4000;
        }
        String constrained=null;
        if(commandRecognizer!=null){
            if(commandFilterActive!=filterCommands){
                commandRecognizer.reset();commandEndpoints.clear();pendingCommand=null;
                commandNoise=filterCommands?new CommandNoise():null;
                commandFilterActive=filterCommands;
            }
            short[] narrow=commandNoise==null?input:commandNoise.apply(pcm,input,count);
            if(commandRecognizer.acceptWaveForm(narrow,count))constrained=text(commandRecognizer.getResult(),"text");
        }
        streamSamples+=count;
        if(archiveReview)rememberPcm(input,count);
        String display=recognizer.acceptWaveForm(input,count)?displayFinal(recognizer.getResult()):null;
        if(commandRecognizer!=null){
            String matched=commandEndpoints.observe(constrained,display==null?null:rawWords,rawPartial,streamSamples);
            if(matched!=null)pendingCommand=matched;
            else if(SelectiveEngine.choiceWord(constrained) && SelectiveEngine.shortChoiceReading(constrained,display==null?rawPartial:rawWords))
                pendingCommand=constrained;
        }
        return display;
    }

    /** A whole command or a trailing named save confirmed by both local decoders. */
    public String pollCommand(){return pollCommand(false);}
    String pollCommand(boolean choosing) {
        String heard = pendingCommand;
        pendingCommand = null;
        return !choosing && SelectiveEngine.choiceWord(heard)?null:heard;
    }

    /** Clear all speech hypotheses at an explicit recording boundary on the decoder thread. */
    public void resetStream() {
        clearRoom();priorContext="";rawPartial="";rawWords="";priorSpeaker=0;priorAt=0;streamSamples=0;used=0;energy=0;overflow=false;quietSpeech.reset();groups.clear();ids.clear();known=null;latestVector=null;voice=0;recognizer.reset();
        if(commandRecognizer!=null)commandRecognizer.reset();
        pendingCommand = null;commandEndpoints.clear();if(commandNoise!=null)commandNoise.reset();
    }

    public String partial() {
        String next=text(recognizer.getPartialResult(),"partial");
        rawPartial=next==null?"":next;
        return spellings.apply("",next);
    }

    void newUtterance(){
        recognizer.reset();if(commandRecognizer!=null)commandRecognizer.reset();
        pendingCommand=null;commandEndpoints.clear();if(commandNoise!=null)commandNoise.reset();rawPartial="";priorContext="";priorSpeaker=0;
    }
    public String finish() {
        return displayFinal(recognizer.getFinalResult());
    }

    private String finalized(String json){
        voice=speakerModel==null?0:-1;known=null;latestVector=null;frames=0;checkWords="";
        try{
            JSONObject words=new JSONObject(json);confidence=confidence(words);weakWord=false;
            JSONArray detail=words.optJSONArray("result");
            checkWords=RecognitionReview.toCheck(detail);
            if(detail!=null)for(int i=0;i<detail.length();i++){
                JSONObject word=detail.optJSONObject(i);
                if(word!=null && word.optDouble("conf",1)<.60){weakWord=true;break;}
            }
            if(speakerModel!=null){
                JSONArray raw=words.optJSONArray("spk");frames=words.optInt("spk_frames",0);
                latestVector=VoiceProfiles.vector(raw);
                if(frames>=100 && latestVector!=null){
                    VoiceProfiles.Match m=VoiceProfiles.identify(profiles.list(),latestVector);
                    known=m.profile;
                    String key=null;
                    if(known!=null)key=known.id;
                    else if(!m.uncertain){int group=groups.match(raw,frames);if(group>0)key="anonymous-"+group;}
                    if(key!=null){Integer id=ids.get(key);if(id==null){id=ids.size()+1;ids.put(key,id);}voice=id;}
                }
            }
            String result=words.optString("text","").trim();return result.isEmpty()?null:result;
        }catch(JSONException invalid){return null;}
    }

    private static String text(String json, String key) {
        try {
            String value = new JSONObject(json).optString(key, "").trim();
            return value.isEmpty() ? null : value;
        } catch (JSONException e) {
            Log.w("TotalRecallSpeech", "Offline recognizer returned an unreadable result");
            return null;
        }
    }

    @Override public void close() {
        if(retry!=null){retry.close();retry=null;}
        if (commandRecognizer != null) {
            commandRecognizer.close();
            commandRecognizer = null;
        }
        if (recognizer != null) {
            recognizer.close();
            recognizer = null;
        }
        if(speakerModel!=null){speakerModel.close();speakerModel=null;}
        if (model != null) {
            model.close();
            model = null;
        }
    }

    /** Build once in private storage. Only a complete staging directory can become the live model. */
    private static synchronized File unpack(Context context,String modelName) throws IOException {
        File base = context.getNoBackupFilesDir();
        File target = new File(base, modelName);
        if (target.isDirectory()) return target;
        File stage = new File(base, modelName + ".installing");
        remove(stage);
        if (!stage.mkdirs()) throw new IOException("Cannot prepare local speech model");
        String prefix = modelName + "/";
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(
                context.getAssets().open("offline-speech/" + modelName + ".zip")))) {
            byte[] buffer = new byte[65536];
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                if (entry.isDirectory()) continue;
                if (!entry.getName().startsWith(prefix)) throw new IOException("Unexpected model contents");
                File out = new File(stage, entry.getName().substring(prefix.length()));
                if (!out.getCanonicalPath().startsWith(stage.getCanonicalPath() + File.separator)) {
                    throw new IOException("Invalid model path");
                }
                File parent = out.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create model directory");
                try (FileOutputStream stream = new FileOutputStream(out)) {
                    for (int n; (n = zip.read(buffer)) != -1;) stream.write(buffer, 0, n);
                }
                zip.closeEntry();
            }
            if(!MODEL.equals(modelName)){
                if(!new File(stage,"final.ext.raw").isFile() || !new File(stage,"transform.mat").isFile())throw new IOException("Bundled speaker model incomplete");
            }else if (!new File(stage, "am/final.mdl").isFile() || !new File(stage, "graph/Gr.fst").isFile()
                    || !new File(stage, "conf/model.conf").isFile()) throw new IOException("Bundled model incomplete");
            if (!stage.renameTo(target)) throw new IOException("Cannot activate offline speech model");
        } finally {
            remove(stage);
        }
        return target;
    }

    private static void remove(File file) throws IOException {
        if (!file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) remove(child);
        if (!file.delete()) throw new IOException("Cannot clear incomplete speech model");
    }
}
