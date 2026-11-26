package org.totalrecall.listening;

import java.util.ArrayList;
import org.json.JSONArray;

/** Temporary, session-scoped speaker clusters. The vectors never leave process memory. */
final class SpeakerGroups {
    private final ArrayList<double[]> centers=new ArrayList<>();
    void clear(){centers.clear();}
    int match(JSONArray embedding,int frames){
        if(embedding==null || embedding.length()!=128 || frames<100)return -1;
        double[] vector=new double[128];
        for(int i=0;i<vector.length;i++)vector[i]=embedding.optDouble(i,Double.NaN);
        if(!normalize(vector))return -1;
        double best=-2,second=-2;int winner=-1;
        for(int i=0;i<centers.size();i++){
            double score=0;double[] center=centers.get(i);
            for(int j=0;j<vector.length;j++)score+=center[j]*vector[j];
            if(score>best){second=best;best=score;winner=i;}else second=Math.max(second,score);
        }
        if(winner>=0 && best>=.64 && best-second>=.07){
            if(best>=.68){double[] center=centers.get(winner);for(int j=0;j<vector.length;j++)center[j]=.85*center[j]+.15*vector[j];normalize(center);}
            return winner+1;
        }
        if(best>.50 || centers.size()>=12)return -1;
        centers.add(vector);return centers.size();
    }
    private static boolean normalize(double[] v){
        double sum=0;for(double n:v)sum+=n*n;
        if(!(sum>0)||!Double.isFinite(sum))return false;
        double magnitude=Math.sqrt(sum);for(int i=0;i<v.length;i++)v[i]/=magnitude;return true;
    }
}
