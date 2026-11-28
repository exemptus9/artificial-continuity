package org.totalrecall.listening;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minimum word edits for one confirmed reference. A repeated word is counted each time. */
final class PhraseCheckScore {
    private static final Pattern WORD=Pattern.compile("[\\p{L}\\p{M}\\p{N}]+(?:['’][\\p{L}\\p{M}\\p{N}]+)*");
    final int words,matches,changed,missed,extra;
    final List<String> differences;

    private PhraseCheckScore(int words,int matches,int changed,int missed,int extra,List<String> differences){
        this.words=words;this.matches=matches;this.changed=changed;this.missed=missed;this.extra=extra;
        this.differences=Collections.unmodifiableList(differences);
    }
    static List<String> words(String text){
        List<String> result=new ArrayList<>();
        Matcher m=WORD.matcher(Normalizer.normalize(text,Normalizer.Form.NFC));
        while(m.find())result.add(m.group());
        return result;
    }
    private static String key(String word){return word.replace("’","").replace("'","").toLowerCase(Locale.ROOT);}
    static boolean samePhrase(String a,String b){return compare(a,b).edits()==0;}
    int edits(){return changed+missed+extra;}
    static PhraseCheckScore compare(String expected,String recognized){
        List<String> reference=words(expected),heard=words(recognized);
        int n=reference.size(),m=heard.size();
        int[][] cost=new int[n+1][m+1];
        for(int i=0;i<=n;i++)cost[i][0]=i;
        for(int j=0;j<=m;j++)cost[0][j]=j;
        for(int i=1;i<=n;i++)for(int j=1;j<=m;j++){
            int replace=key(reference.get(i-1)).equals(key(heard.get(j-1)))?0:1;
            cost[i][j]=Math.min(cost[i-1][j-1]+replace,1+Math.min(cost[i-1][j],cost[i][j-1]));
        }
        int i=n,j=m,matched=0,changed=0,missed=0,extra=0;
        List<String> details=new ArrayList<>();
        while(i>0 || j>0){
            if(i>0 && j>0 && key(reference.get(i-1)).equals(key(heard.get(j-1))) && cost[i][j]==cost[i-1][j-1]){
                matched++;i--;j--;
            }else if(i>0 && j>0 && cost[i][j]==cost[i-1][j-1]+1){
                details.add("Changed “"+reference.get(--i)+"” to “"+heard.get(--j)+"”");changed++;
            }else if(i>0 && cost[i][j]==cost[i-1][j]+1){
                details.add("Missed “"+reference.get(--i)+"”");missed++;
            }else{
                details.add("Added “"+heard.get(--j)+"”");extra++;
            }
        }
        Collections.reverse(details);
        return new PhraseCheckScore(n,matched,changed,missed,extra,details);
    }
}
