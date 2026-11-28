package org.totalrecall.listening;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Evidence from final local decodes. A low score invites review, never an invented replacement. */
final class RecognitionReview {
    private static final Set<String> SENSITIVE=new HashSet<>(Arrays.asList(
        "no not never neither nor none nobody nothing nowhere cannot can't cant won't wont don't dont doesn't doesnt didn't didnt shouldn't shouldnt wouldn't wouldnt couldn't couldnt isn't isnt aren't arent wasn't wasnt weren't werent haven't havent hasn't hasnt hadn't hadnt without except unless zero oh one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen twenty thirty forty fifty sixty seventy eighty ninety hundred thousand million billion half quarter double twice".split(" ")));

    private static String word(JSONArray rows,int i){
        JSONObject item=rows.optJSONObject(i);
        return item==null?"":item.optString("word","").trim();
    }
    private static double score(JSONArray rows,int i){
        JSONObject item=rows.optJSONObject(i);
        return item==null?Double.NaN:item.optDouble("conf",Double.NaN);
    }
    static String toCheck(JSONArray rows){
        if(rows==null)return "";
        Set<String> words=new LinkedHashSet<>();
        for(int i=0;i<rows.length() && words.size()<3;i++){
            String token=word(rows,i);
            double value=score(rows,i);
            if(value>=0 && value<=.55 && !token.isEmpty() && token.length()<=64 && !token.equals("[unk]"))
                words.add(token);
        }
        return String.join(", ",words);
    }
    private static boolean sensitive(String word){
        return SENSITIVE.contains(word) || word.endsWith("n't") || word.matches(".*[0-9].*");
    }
    private static String anchors(String phrase){
        StringBuilder result=new StringBuilder();
        for(String word:phrase.toLowerCase(Locale.ROOT).split(" +"))if(sensitive(word))result.append(' ').append(word);
        return result.toString();
    }
    static boolean safeReplacement(String original,String proposed){
        if(original==null)return anchors(proposed).isEmpty();
        if(original.equals(proposed))return true;
        return !SpeechContext.literal(original) && anchors(original).equals(anchors(proposed));
    }

    // Ordinals, ranges and arithmetic signs also carry meaning in recordings read for comparison.
    private static final Set<String> FOCUS_NUMBERS=new HashSet<>(Arrays.asList(
        "first second third fourth fifth sixth seventh eighth ninth tenth eleventh twelfth thirteenth fourteenth fifteenth sixteenth seventeenth eighteenth nineteenth twentieth thirtieth fortieth fiftieth sixtieth seventieth eightieth ninetieth hundredth thousandth millionth plus minus negative point dozen single triple".split(" ")));
    static boolean safeFocusReplacement(String original,String proposed){
        if(!safeReplacement(original,proposed))return false;
        String before=original==null?"":original;
        return focusNumbers(before).equals(focusNumbers(proposed));
    }
    private static String focusNumbers(String words){
        StringBuilder numbers=new StringBuilder();
        for(String token:words.toLowerCase(Locale.ROOT).split(" +"))if(FOCUS_NUMBERS.contains(token))numbers.append(' ').append(token);
        return numbers.toString();
    }

    // Align each retry to the original. An utterance average can conceal a poorly supported
    // replacement midway through an otherwise clear sentence.
    static boolean supportedRewrite(String original,JSONArray first,String reading,JSONArray retry){
        if(!safeReplacement(original,reading) || retry==null)return false;
        if(reading.equals(original))return true;
        String[] from=original==null || original.isEmpty()?new String[0]:original.split(" +");
        String[] to=reading.split(" +");
        if(to.length!=retry.length() || to.length>160 || from.length>160 ||
            (from.length>0 && (first==null || first.length()!=from.length)))return false;
        for(int j=0;j<to.length;j++)if(!to[j].equals(word(retry,j)))return false;
        for(int i=0;i<from.length;i++)if(!from[i].equals(word(first,i)))return false;
        int[][] cost=new int[from.length+1][to.length+1];
        for(int i=0;i<=from.length;i++)cost[i][0]=i;
        for(int j=0;j<=to.length;j++)cost[0][j]=j;
        for(int i=1;i<=from.length;i++)for(int j=1;j<=to.length;j++){
            int substitution=cost[i-1][j-1]+(from[i-1].equals(to[j-1])?0:1);
            cost[i][j]=Math.min(substitution,1+Math.min(cost[i-1][j],cost[i][j-1]));
        }
        int i=from.length,j=to.length;
        while(i>0 || j>0){
            if(i>0 && j>0 && from[i-1].equals(to[j-1]) && cost[i][j]==cost[i-1][j-1]){i--;j--;continue;}
            if(i>0 && j>0 && cost[i][j]==cost[i-1][j-1]+1){
                if(!(score(retry,j-1)>=.85) || !(score(first,i-1)<.90))return false;
                i--;j--;
            }else if(j>0 && cost[i][j]==cost[i][j-1]+1){
                if(!(score(retry,j-1)>=.85))return false;
                j--;
            }else{
                if(!(score(first,i-1)<=.55))return false;
                i--;
            }
        }
        return true;
    }

    // Locate one interior word by its immediate left/right neighbors, even if the decoder
    // inserted or dropped a word elsewhere. Repeated contexts are ambiguous and abstain.
    private static int uniqueContext(JSONArray words,String left,String right){
        int found=-1;
        for(int i=1;i+1<words.length();i++){
            if(!left.equals(word(words,i-1)) || !right.equals(word(words,i+1)))continue;
            if(found>=0)return -1;
            found=i;
        }
        return found;
    }
    static String repair(String original,JSONArray first,JSONArray a,JSONArray b){
        if(original==null || first==null || a==null || b==null || SpeechContext.literal(original))return null;
        String[] source=original.split(" +");
        if(source.length<3 || source.length!=first.length() || source.length>160 || a.length()>180 || b.length()>180)return null;
        for(int i=0;i<source.length;i++)if(!source[i].equals(word(first,i)))return null;
        String[] corrected=source.clone();
        boolean changed=false;
        for(int i=1;i+1<source.length;i++){
            double initial=score(first,i);
            if(!(initial>=0 && initial<=.55) || sensitive(source[i]))continue;
            String left=source[i-1],right=source[i+1];
            if(uniqueContext(first,left,right)!=i)continue;
            int j=uniqueContext(a,left,right),k=uniqueContext(b,left,right);
            if(j<0 || k<0 || !(score(a,j)>=.92 && score(b,k)>=.92))continue;
            String next=word(a,j);
            if(next.isEmpty() || next.equals(source[i]) || !next.equals(word(b,k)) || sensitive(next))continue;
            corrected[i]=next;changed=true;
            i++; // Never let two adjacent repairs act as each other's anchors.
        }
        return changed?String.join(" ",corrected):null;
    }
}
