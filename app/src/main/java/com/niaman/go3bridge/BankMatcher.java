package com.niaman.go3bridge;

import java.util.*;

public class BankMatcher {
    static class Match {
        final String block;
        final double score;
        Match(String block,double score){ this.block=block; this.score=score; }
    }

    static Match best(String question,String bank){
        if(bank==null||bank.trim().isEmpty())return null;
        String[] blocks=bank.split("(?:\\r?\\n){2,}");
        if(blocks.length<5){
            String[] lines=bank.split("\\r?\\n");
            ArrayList<String> list=new ArrayList<>();
            for(int i=0;i<lines.length;i+=8){
                StringBuilder sb=new StringBuilder();
                for(int j=i;j<Math.min(i+8,lines.length);j++)sb.append(lines[j]).append("\n");
                list.add(sb.toString());
            }
            blocks=list.toArray(new String[0]);
        }

        Set<String> q=tokens(question);
        Match best=null;
        for(String block:blocks){
            if(block.trim().length()<8)continue;
            double s=jaccard(q,tokens(block));
            if(best==null||s>best.score)best=new Match(block,s);
        }
        return best;
    }

    private static Set<String> tokens(String s){
        String n=s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+"," ").trim();
        HashSet<String> out=new HashSet<>();
        if(n.isEmpty())return out;
        for(String w:n.split("\\s+"))if(w.length()>=2)out.add(w);
        return out;
    }

    private static double jaccard(Set<String>a,Set<String>b){
        if(a.isEmpty()||b.isEmpty())return 0;
        int inter=0;
        for(String x:a)if(b.contains(x))inter++;
        int union=a.size()+b.size()-inter;
        return union==0?0:(double)inter/union;
    }
}
