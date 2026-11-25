package org.totalrecall.listening;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

public final class ClipArchiveTest {
    static final String uid="b6425206-6e17-4710-a03b-35bc26cd96dc-1", path="audio/"+uid+".wav";
    static byte[] bundle(byte[] wav,JSONArray rows,String name)throws Exception{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(ZipOutputStream zip=new ZipOutputStream(bytes)){
            zip.putNextEntry(new ZipEntry(name));zip.write(wav);zip.closeEntry();
            zip.putNextEntry(new ZipEntry("index.json"));zip.write(new JSONObject().put("schema","total-recall-listening/v1")
                .put("clips",rows).toString().getBytes(StandardCharsets.UTF_8));zip.closeEntry();
        }return bytes.toByteArray();
    }
    static ClipArchive read(byte[] bytes)throws Exception{
        Path temp=Files.createTempDirectory("recall-stage-case-");
        try{return ClipArchive.stage(new ByteArrayInputStream(bytes),temp.toFile());}
        finally{for(File child:temp.toFile().listFiles())child.delete();Files.delete(temp);}
    }
    static void rejects(byte[] bytes)throws Exception{
        try{read(bytes);}catch(IOException expected){return;}throw new AssertionError("Malformed archive accepted");
    }
    public static void main(String[] a)throws Exception{
        File file=File.createTempFile("recall-wav-test-",".wav");
        short[] pcm=new short[3203];for(int i=0;i<pcm.length;i++)pcm[i]=(short)(i*5);
        String hash=Store.writeWav(file,pcm);byte[] wav=Files.readAllBytes(file.toPath());file.delete();
        JSONObject row=new JSONObject().put("id",uid).put("audio",path).put("sha256",hash).put("startedAtMillis",100L)
          .put("durationMs",200L).put("feedback","keep").put("transcript","The test").put("reason","Test").put("title","Test").put("category","Test");
        JSONArray rows=new JSONArray().put(row);
        if(read(bundle(wav,rows,path)).clips.size()!=1)throw new AssertionError();
        rejects(bundle(wav,rows,"../bad.wav"));
        rejects(bundle(wav,new JSONArray().put(row).put(row),path));
        rejects(bundle(wav,new JSONArray(),path));
        row.put("durationMs",201);rejects(bundle(wav,rows,path));row.put("durationMs",200);
        row.put("id","../bad");rejects(bundle(wav,rows,path));row.put("id",uid);
        byte[] corrupt=wav.clone();corrupt[44]^=1;rejects(bundle(corrupt,rows,path));
        corrupt=wav.clone();corrupt[0]^=1;row.put("sha256",Store.hex(java.security.MessageDigest.getInstance("SHA-256").digest(corrupt)));
        rejects(bundle(corrupt,rows,path));
        System.out.println("PASS: actual WAV and digest round trip; traversal, duplicate/missing references, duration, ID, altered audio and invalid format rejected.");
    }
}
