package org.totalrecall.listening;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** Bridge from synthetic acoustic replay to the checkout's actual PCM front end and command selector. */
public final class CommandReplay {
    private static final int FRAME=1600;
    private final List<String> effects=new ArrayList<>();
    private SelectiveEngine engine;
    private SelectiveEngine.CommandEndpoints endpoints;
    private MicSignal signal;
    private ByteBuffer original;
    private int elapsed;
    private String name,previousPartial;
    private static ByteBuffer read(String path)throws Exception{
        return ByteBuffer.wrap(Files.readAllBytes(Paths.get(path))).order(ByteOrder.LITTLE_ENDIAN);
    }
    private void start(String[] fields)throws Exception{
        effects.clear();name=fields[1];original=read(fields[2]);previousPartial="";elapsed=0;
        endpoints=new SelectiveEngine.CommandEndpoints();signal=new MicSignal();
        engine=new SelectiveEngine(new SelectiveEngine.Listener(){
            public void onClip(SelectiveEngine.Clip clip){effects.add("clip="+clip.category);}
            public void onPauseRequested(){effects.add("pause");}
            public void onVoice(String text,boolean wake){if(wake)effects.add("wake@"+elapsed);}
            public void onSession(int action){effects.add("session="+action);}
            public void onSpokenRequest(String request){if(!request.equals("ready"))effects.add("voice="+request);}
        },0L);
    }
    private void score(String[] fields){
        String words=fields[1],partial=fields[2],restricted=fields[3];
        String matched=endpoints.observe(restricted.isEmpty()?null:restricted,words.isEmpty()?null:words,
                previousPartial,(long)(elapsed+100)*16);
        if(!words.isEmpty())engine.text(words,true);
        else if(matched==null && !partial.isEmpty())engine.text(partial,false);
        if(matched!=null && !matched.equals(words))engine.text(matched,true);
        if(!words.isEmpty())previousPartial="";else if(!partial.isEmpty())previousPartial=partial;
        int count=Math.min(FRAME,original.remaining()/2);short[] frame=new short[count];
        for(int i=0;i<count;i++)frame[i]=original.getShort();
        signal.observe(frame,count);engine.pcm(frame,count,signal.audible());elapsed+=count/16;
    }
    private static void prepare(String[] paths,boolean denoise)throws Exception {
        ByteBuffer input=read(paths[0]);
        ByteBuffer full=ByteBuffer.allocate(input.remaining()).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer commands=ByteBuffer.allocate(input.remaining()).order(ByteOrder.LITTLE_ENDIAN);
        QuietSpeech gain=new QuietSpeech();MicSignal signal=new MicSignal();
        // A previous checkout can be benchmarked even if it predates the optional noise filter.
        Object noise=null;Method filter=null;
        if(denoise){
            Class<?> type=Class.forName("org.totalrecall.listening.CommandNoise");
            noise=type.getDeclaredConstructor().newInstance();
            filter=type.getDeclaredMethod("apply",short[].class,short[].class,int.class);
        }
        short[] frame=new short[FRAME];
        while(input.hasRemaining()){
            int size=Math.min(FRAME,input.remaining()/2);
            for(int i=0;i<size;i++)frame[i]=input.getShort();
            signal.observe(frame,size);short[] common=gain.apply(frame,size,signal.gainLimit(false));
            short[] narrow=denoise?(short[])filter.invoke(noise,frame,common,size):common;
            for(int i=0;i<size;i++){full.putShort(common[i]);commands.putShort(narrow[i]);}
        }
        Files.write(Paths.get(paths[1]),full.array());Files.write(Paths.get(paths[1]+".cmd"),commands.array());
    }
    public static void main(String[] args)throws Exception {
        if(args[0].equals("grammar")){
            for(String wake:SelectiveEngine.WAKES){
                System.out.println(wake);
                for(String[] group:SelectiveEngine.COMMANDS)
                    for(int i=1;i<group.length;i++)System.out.println(wake+" "+group[i]);
            }
            for(String[] group:SelectiveEngine.COMMANDS)
                for(int i=1;i<group.length;i++)System.out.println(group[i]);
            try{
                Field labels=SelectiveEngine.class.getDeclaredField("CHOICE_WORDS");
                for(String label:(String[])labels.get(null))System.out.println(label);
            }catch(NoSuchFieldException previousVersion){ /* No two-step choice in v0.29. */ }
            System.out.println("[unk]");return;
        }
        CommandReplay replay=new CommandReplay();
        try(BufferedReader input=new BufferedReader(new InputStreamReader(System.in))){
            String row;
            while((row=input.readLine())!=null){
                String[] fields=row.split("\t",-1);
                if(args[0].equals("boost"))prepare(fields,args.length>1);
                else if(fields[0].equals("start"))replay.start(fields);
                else if(fields[0].equals("end"))System.out.println(replay.name+"\t"+String.join("|",replay.effects));
                else replay.score(fields);
            }
        }
    }
}
