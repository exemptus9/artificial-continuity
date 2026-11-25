package org.totalrecall.listening;

import java.io.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;


/** Canonical little-endian monaural 16 kHz PCM used by Store and its transfer format. */
final class PcmExcerpt {
    static final int RATE=16000,HEADER=44;
    private PcmExcerpt(){}
    static int count(File audio)throws IOException {
        try(RandomAccessFile file=new RandomAccessFile(audio,"r")){
            byte[] head=new byte[HEADER];file.readFully(head);long bytes=file.length()-HEADER;
            if(bytes<=0||bytes>Integer.MAX_VALUE-36||(bytes&1)!=0 ||
                !tag(head,0,"RIFF")||!tag(head,8,"WAVEfmt ")||!tag(head,36,"data")||
                get(head,4)!=bytes+36||get(head,16)!=16||get(head,20)!=65537||
                get(head,24)!=RATE||get(head,28)!=RATE*2||get(head,32)!=0x100002||get(head,40)!=bytes)
                throw new IOException("Unsupported or incomplete clip audio");
            return (int)(bytes/2);
        }
    }
    private static boolean tag(byte[] h,int offset,String s){
        for(int i=0;i<s.length();i++)if(h[offset+i]!=(byte)s.charAt(i))return false;
        return true;
    }
    private static int get(byte[] h,int i){
        return (h[i]&255)|((h[i+1]&255)<<8)|((h[i+2]&255)<<16)|((h[i+3]&255)<<24);
    }
    private static void put(byte[] h,int i,int value){
        for(int j=0;j<4;j++)h[i+j]=(byte)(value>>>(8*j));
    }
    static int[] peaks(File source,int columns)throws IOException{
        if(columns<=0||columns>16384)throw new IllegalArgumentException("Invalid waveform resolution");
        int count=count(source);int[] peaks=new int[columns];
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(new FileInputStream(source)))){
            in.readFully(new byte[HEADER]);byte[] block=new byte[8192];int index=0;
            while(index<count){
                int n=Math.min(count-index,block.length/2);in.readFully(block,0,n*2);
                for(int i=0;i<n;i++){
                    int amplitude=Math.abs((int)(short)((block[2*i]&255)|(block[2*i+1]<<8)));
                    int bin=(int)(((long)(index+i)*columns)/count);
                    peaks[bin]=Math.max(peaks[bin],amplitude);
                }
                index+=n;
            }
        }return peaks;
    }
    static String trim(File source,File output,int start,int end)throws IOException{
        int count=count(source);
        if(source.getCanonicalFile().equals(output.getCanonicalFile()))throw new IOException("Choose a separate output file for the excerpt");
        if(start<0||end<=start||end>count)throw new IOException("Choose a nonempty range within the original clip");
        try(RandomAccessFile input=new RandomAccessFile(source,"r");FileOutputStream out=new FileOutputStream(output)){
            MessageDigest hash;
            try{hash=MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}
            int frames=end-start, bytes=frames*2;
            byte[] head=new byte[HEADER];input.readFully(head);put(head,4,bytes+36);put(head,40,bytes);
            hash.update(head);out.write(head);input.seek(HEADER+start*2L);
            byte[] block=new byte[16384];int remaining=bytes;
            while(remaining>0){int n=Math.min(remaining,block.length);input.readFully(block,0,n);
                out.write(block,0,n);hash.update(block,0,n);remaining-=n;}
            out.flush();out.getFD().sync();
            char[] alphabet="0123456789abcdef".toCharArray(),result=new char[64];int j=0;
            for(byte value:hash.digest()){result[j++]=alphabet[(value&255)>>>4];result[j++]=alphabet[value&15];}
            return new String(result);
        }
    }
}
