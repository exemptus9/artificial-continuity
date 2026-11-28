package org.totalrecall.listening;

import android.content.Context;
import android.os.SystemClock;
import org.tensorflow.lite.Interpreter;
import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Operates on a bounded copy of raw input. Inference never holds up the recorder. */
final class SoundScene implements AutoCloseable {
    static final int WINDOW=15600;
    private final Context app;
    private final ArrayBlockingQueue<Frame> queue=new ArrayBlockingQueue<>(1);
    private final Thread worker;
    private short[] samples=new short[WINDOW];
    private int used,sequence;
    private volatile boolean closed;
    private volatile int generation;

    SoundScene(Context context){
        app=context.getApplicationContext();
        worker=new Thread(this::run,"Recall sound recognition");worker.setDaemon(true);worker.start();
    }
    private static final class Frame {
        final int generation,sequence; final long time; final short[] samples;
        Frame(int g,int n,short[] pcm){generation=g;sequence=n;samples=pcm;time=SystemClock.elapsedRealtime();}
    }
    void feed(short[] pcm,int count){
        for(int pos=0;pos<count;){
            int copy=Math.min(count-pos,WINDOW-used);
            System.arraycopy(pcm,pos,samples,used,copy);used+=copy;pos+=copy;
            if(used==WINDOW){
                sequence++;
                if(queue.offer(new Frame(generation,sequence,samples)))samples=new short[WINDOW];
                used=0;
            }
        }
    }
    synchronized void reset(){
        generation++;used=0;queue.clear();LiveCapture.sound("",0);
    }
    @Override public synchronized void close(){closed=true;reset();worker.interrupt();}
    static final class Model implements AutoCloseable {
        private final Interpreter interpreter;
        private final List<String> names=new ArrayList<>();
        private final List<int[]> ids=new ArrayList<>();
        private final float[] input=new float[WINDOW];
        private final float[][] output=new float[1][521];
        private final Set<String> previous=new HashSet<>();
        Model(Context c)throws IOException{
            try(BufferedReader rows=new BufferedReader(new InputStreamReader(c.getAssets().open("listening/sound-groups.tsv"),"UTF-8"))){
                for(String line;(line=rows.readLine())!=null;){
                    String[] fields=line.split("\\t");names.add(fields[0]);
                    ArrayList<Integer> indexes=new ArrayList<>();
                    for(String run:fields[1].split(",")){
                        String[] ends=run.split("-");
                        for(int i=Integer.parseInt(ends[0]);i<=Integer.parseInt(ends[ends.length-1]);i++)indexes.add(i);
                    }
                    int[] index=new int[indexes.size()];for(int i=0;i<index.length;i++)index[i]=indexes.get(i);
                    ids.add(index);
                }
            }
            try(InputStream in=c.getAssets().open("offline-speech/yamnet.tflite")){
                ByteBuffer model=ByteBuffer.allocateDirect(4126810).order(ByteOrder.nativeOrder());
                byte[] chunk=new byte[16384];int n;
                while((n=in.read(chunk))!=-1)model.put(chunk,0,n);
                model.rewind();interpreter=new Interpreter(model,new Interpreter.Options().setNumThreads(1));
            }
        }
        void restart(){previous.clear();}
        String recognize(short[] pcm){
            for(int i=0;i<WINDOW;i++)input[i]=pcm[i]/32768f;
            interpreter.run(input,output);
            Set<String> next=new HashSet<>();String first="",second="";float high=-1,low=-1;
            for(int g=0;g<ids.size();g++){
                String name=names.get(g);float value=0;
                for(int index:ids.get(g))value=Math.max(value,output[0][index]);
                if(value<.36f)continue;
                next.add(name);
                if(value<.70f && !previous.contains(name))continue;
                if(value>high){second=first;low=high;first=name;high=value;}
                else if(value>low){second=name;low=value;}
            }
            previous.clear();previous.addAll(next);
            return second.isEmpty()?first:first+" + "+second;
        }
        @Override public void close(){interpreter.close();}
    }
    private void run(){
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
        try(Model model=new Model(app)){
            int lastGeneration=-1,last=-1;
            while(!closed){
                Frame f=queue.poll(500,TimeUnit.MILLISECONDS);
                if(f==null || f.generation!=generation || SystemClock.elapsedRealtime()-f.time>2000)continue;
                if(lastGeneration!=f.generation || last+1!=f.sequence)model.restart();
                String name=model.recognize(f.samples);
                lastGeneration=f.generation;last=f.sequence;
                synchronized(this){if(!closed && generation==f.generation)LiveCapture.sound(name.isEmpty()?"No clear sound match":"Possible · "+name,f.time);}
            }
        }catch(InterruptedException ignored){Thread.currentThread().interrupt();}
        catch(IOException|RuntimeException|LinkageError failure){
            synchronized(this){if(!closed)LiveCapture.sound("Sound recognition unavailable",Long.MAX_VALUE);}
        }
    }
}
