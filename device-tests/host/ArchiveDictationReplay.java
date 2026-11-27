package org.totalrecall.listening;

import android.content.Context;
import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Host-only evaluation of the shipped archive path. No microphone, enrollment, or user data. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,manifest=Config.NONE)
public class ArchiveDictationReplay {
    @Test public void archivePasses() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        Path corpus=Paths.get(System.getProperty("recall.corpus"));
        Path model=Paths.get(System.getProperty("recall.model"));
        Files.createSymbolicLink(context.getNoBackupFilesDir().toPath().resolve(model.getFileName()),model);
        List<SpeechContext.Rule> defaults=new ArrayList<>();
        for(String line:Files.readAllLines(Paths.get(System.getProperty("recall.spellings")))){
            String[] pair=line.split("\t",2);
            defaults.add(new SpeechContext.Rule(pair[0],pair[1]));
        }
        Field stock=SpeechContext.class.getDeclaredField("generic");
        stock.setAccessible(true);stock.set(null,defaults);
        Method hint=null;
        try{hint=OfflineSpeech.class.getDeclaredMethod("takeReviewHint");}
        catch(NoSuchMethodException oldVersion){}
        JSONArray cases=new JSONArray(new String(Files.readAllBytes(corpus.resolve("cases.json")),"UTF-8"));
        try(OfflineSpeech speech=new OfflineSpeech(context,false,false);
            PrintWriter output=new PrintWriter(System.getProperty("recall.report"),"UTF-8")){
            for(int i=0;i<cases.length();i++){
                JSONObject fixture=cases.getJSONObject(i);
                speech.resetStream();
                int compared=speech.compared,agreed=speech.agreed;
                ByteBuffer input=ByteBuffer.wrap(Files.readAllBytes(corpus.resolve(fixture.getString("file")))).order(ByteOrder.LITTLE_ENDIAN);
                short[] frame=new short[1600];
                JSONArray readings=new JSONArray();
                while(input.hasRemaining()){
                    int count=Math.min(frame.length,input.remaining()/2);
                    for(int j=0;j<count;j++)frame[j]=input.getShort();
                    record(speech,hint,speech.accept(frame,count),readings);
                }
                record(speech,hint,speech.finish(),readings);
                fixture.put("readings",readings).put("compared",speech.compared-compared).put("agreed",speech.agreed-agreed);
                output.println(fixture.toString());output.flush();
                if(output.checkError())throw new IOException("Could not write dictation replay");
            }
        }
    }
    private static void record(OfflineSpeech speech,Method hint,String shown,JSONArray rows) throws Exception {
        String review=hint==null?"":(String)hint.invoke(speech);
        if(shown!=null || !review.isEmpty())rows.put(new JSONObject().put("first",speech.rawWords())
            .put("shown",shown==null?"":shown).put("hint",review));
    }
}
