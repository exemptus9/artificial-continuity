package org.totalrecall.listening;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.zip.*;
import org.json.*;

/** Stage only this app's index and bounded PCM WAV entries, then validate every reference before import. */
final class ClipArchive {
    static final int MAX_CLIPS=4096,MAX_WAV=16*1024*1024,MAX_INDEX=16*1024*1024;
    static final long MAX_TOTAL=512L*1024*1024;
    static final String ID="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-[1-9][0-9]{0,17}";
    final ArrayList<JSONObject> clips=new ArrayList<>();
    final Map<String,File> audio=new HashMap<>();
    private static String text(JSONObject o,String k,int max)throws JSONException,IOException{
        Object value=o.get(k);
        if(!(value instanceof String) || ((String)value).length()>max)throw new IOException("Invalid field: "+k);
        return (String)value;
    }
    private static long number(JSONObject o,String k)throws IOException,JSONException{
        Object v=o.get(k);if(!(v instanceof Integer || v instanceof Long))throw new IOException("Invalid timestamp or duration");
        return ((Number)v).longValue();
    }
    private static int le(byte[] b,int i){return (b[i]&255)|(b[i+1]&255)<<8|(b[i+2]&255)<<16|(b[i+3]&255)<<24;}
    static long duration(File f)throws IOException{
        byte[] h=new byte[44];try(DataInputStream in=new DataInputStream(new FileInputStream(f))){in.readFully(h);}
        long data=f.length()-44;
        if(!"RIFF".equals(new String(h,0,4,StandardCharsets.US_ASCII)) ||
           !"WAVEfmt ".equals(new String(h,8,8,StandardCharsets.US_ASCII)) ||
           !"data".equals(new String(h,36,4,StandardCharsets.US_ASCII)) ||
           le(h,4)!=data+36 || le(h,16)!=16 || le(h,20)!=0x10001 || le(h,24)!=16000 ||
           le(h,28)!=32000 || le(h,32)!=0x100002 || le(h,40)!=data || data<=0 || (data&1)!=0)
            throw new IOException("Unsupported or truncated PCM WAV");
        return data*1000/32000;
    }
    static ClipArchive stage(InputStream input,File temp)throws IOException{
        ClipArchive archive=new ClipArchive(); byte[] index=null, buf=new byte[32768];
        long total=0; Map<String,String> hashes=new HashMap<>();
        try(ZipInputStream zip=new ZipInputStream(new BufferedInputStream(input))){
            for(ZipEntry e;(e=zip.getNextEntry())!=null;){
                String path=e.getName(); boolean manifest=path.equals("index.json");
                if(!manifest&&!path.matches("audio/"+ID+"\\.wav"))throw new IOException("Unexpected ZIP entry");
                if(manifest && index!=null || archive.audio.containsKey(path))throw new IOException("Duplicate ZIP entry");
                if(!manifest && archive.audio.size()>=MAX_CLIPS)throw new IOException("Too many audio files");
                File file=manifest?null:new File(temp,path.substring(6));
                ByteArrayOutputStream bytes=manifest?new ByteArrayOutputStream():null;
                MessageDigest sha;
                try{sha=MessageDigest.getInstance("SHA-256");}catch(GeneralSecurityException ex){throw new AssertionError(ex);}
                int size=0;
                try(OutputStream out=manifest?bytes:new BufferedOutputStream(new FileOutputStream(file))){
                    int n;while((n=zip.read(buf))!=-1){
                        size+=n;total+=n;
                        if(size>(manifest?MAX_INDEX:MAX_WAV)||total>MAX_TOTAL)throw new IOException("Archive exceeds restore limits");
                        out.write(buf,0,n);if(!manifest)sha.update(buf,0,n);
                    }
                }
                if(manifest)index=bytes.toByteArray();
                else{archive.audio.put(path,file);hashes.put(path,Store.hex(sha.digest()));}
                zip.closeEntry();
            }
        }
        if(index==null)throw new IOException("Missing listening index");
        try{
            JSONObject doc=new JSONObject(new String(index,StandardCharsets.UTF_8));
            if(!"total-recall-listening/v1".equals(doc.optString("schema")))throw new IOException("Unsupported ZIP format");
            JSONArray rows=doc.getJSONArray("clips");
            if(rows.length()!=hashes.size())throw new IOException("Clip index and media count differ");
            Set<String> ids=new HashSet<>();
            for(int i=0;i<rows.length();i++){
                JSONObject row=rows.getJSONObject(i);
                String id=text(row,"id",80),path=text(row,"audio",100),hash=text(row,"sha256",64);
                if(!id.matches(ID)||!ids.add(id)||!path.equals("audio/"+id+".wav")||!hash.equals(hashes.get(path)))
                    throw new IOException("Clip ID, path or audio digest mismatch");
                long dur=number(row,"durationMs"),start=number(row,"startedAtMillis");
                if(start<0||start>Long.MAX_VALUE-dur||dur!=duration(archive.audio.get(path)))throw new IOException("Clip duration or timestamp mismatch");
                if(row.has("parentId")||row.has("startSample")||row.has("endSample")){
                    String parent=text(row,"parentId",80);long from=number(row,"startSample"),to=number(row,"endSample");
                    long samples=(archive.audio.get(path).length()-44)/2;
                    if(!parent.matches(ID)||id.equals(parent)||from<0||to<=from||to>Integer.MAX_VALUE||to-from!=samples)
                        throw new IOException("Invalid excerpt provenance");
                }
                for(String key:new String[]{"transcript","title","category","reason","feedback"})text(row,key,1_000_000);
                for(String key:new String[]{"note","correction"})if(row.has(key))text(row,key,1_000_000);
                if(!Arrays.asList("none","keep","skip").contains(row.getString("feedback")))throw new IOException("Unsupported feedback value");
                archive.clips.add(row);
            }
        }catch(JSONException e){throw new IOException("Malformed clip index",e);}
        return archive;
    }
}
