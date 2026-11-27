package org.totalrecall.listening;

import java.util.*;
import java.util.regex.Pattern;

/** Extract only the part of a confirmed utterance whose spelling the speaker corrected. */
final class VoiceWording {
    final String heard,wanted;
    private final Pattern phrase;
    VoiceWording(String heard,String wanted){
        this.heard=heard;this.wanted=wanted;
        phrase=Pattern.compile("(?<![\\p{L}\\p{N}'])"+Pattern.quote(heard)+"(?![\\p{L}\\p{N}'])",Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE);
    }
    SpeechContext.Rule rule(){
        return new SpeechContext.Rule(wanted,"("+phrase.pattern()+")");
    }
    static VoiceWording difference(String heard,String wanted){
        if(heard==null||wanted==null||wanted.trim().isEmpty())return null;
        String[] a=heard.trim().split("\\s+"),b=wanted.trim().split("\\s+");
        int left=0,i=a.length,j=b.length;
        while(left<i && left<j && a[left].equals(b[left]))left++;
        while(i>left && j>left && a[i-1].equals(b[j-1])){i--;j--;}
        if(left==i && left==j)return null;
        // Keep a neighboring word for a single ambiguous sound or an insertion/deletion.
        if(i-left<2 || j==left){if(left>0)left--;else if(i<a.length && j<b.length){i++;j++;}}
        String from=String.join(" ",Arrays.copyOfRange(a,left,i)),to=String.join(" ",Arrays.copyOfRange(b,left,j));
        if(!SpeechContext.valid(from)||!SpeechContext.valid(to)||i-left>10||j-left>10)
            throw new IllegalArgumentException("Correct one short phrase at a time, or leave the wording as heard.");
        return new VoiceWording(from,to);
    }
}
