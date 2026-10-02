package com.niaman.go3bridge;

import java.util.*;
import java.util.regex.*;

public class QuestionBank {
    static class Entry {
        final int ticket;
        final int question;
        final String block;
        final int correctIndex;
        final String correctText;
        Entry(int ticket,int question,String block,int correctIndex,String correctText){
            this.ticket=ticket;
            this.question=question;
            this.block=block;
            this.correctIndex=correctIndex;
            this.correctText=correctText;
        }
        String answer(){
            if(correctIndex<=0)return "";
            if(correctText!=null&&!correctText.trim().isEmpty())
                return correctIndex+". "+correctText.trim();
            return String.valueOf(correctIndex);
        }
    }

    static class Match {
        final Entry entry;
        final double score;
        final double secondScore;
        Match(Entry entry,double score,double secondScore){
            this.entry=entry; this.score=score; this.secondScore=secondScore;
        }
        boolean reliable(){
            if(entry==null||entry.correctIndex<=0)return false;
            if(score>=0.52)return true;
            return score>=0.36 && (score-secondScore)>=0.055;
        }
    }

    private final ArrayList<Entry> entries=new ArrayList<>();

    static QuestionBank parse(String raw){
        QuestionBank qb=new QuestionBank();
        if(raw==null)return qb;
        String text=raw.replace('\u00A0',' ').replace("\r","");
        Map<Integer,int[]> answers=parseAnswerTable(text);

        String[] lines=text.split("\n");
        int ticket=0, qnum=0;
        StringBuilder buf=null;

        Pattern ticketPat=Pattern.compile("^\\s*Билет\\s*№\\s*(\\d+)\\s*$",Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE);
        Pattern qPat=Pattern.compile("^\\s*Вопрос\\s*№\\s*(\\d+)\\s*$",Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE);

        for(String line:lines){
            String t=line.trim();
            Matcher tm=ticketPat.matcher(t);
            if(tm.matches()){
                if(buf!=null&&ticket>0&&qnum>0)qb.add(ticket,qnum,buf.toString(),answers);
                ticket=Integer.parseInt(tm.group(1));
                qnum=0; buf=null;
                continue;
            }
            Matcher qm=qPat.matcher(t);
            if(qm.matches()&&ticket>0){
                if(buf!=null&&qnum>0)qb.add(ticket,qnum,buf.toString(),answers);
                qnum=Integer.parseInt(qm.group(1));
                buf=new StringBuilder();
                continue;
            }
            if(buf!=null){
                if(t.startsWith("https://pddmaster.ru/"))continue;
                if(t.matches("^\\d{1,3}$"))continue;
                if(t.equalsIgnoreCase("Таблица правильных ответов")){
                    qb.add(ticket,qnum,buf.toString(),answers);
                    buf=null; qnum=0; ticket=0;
                    break;
                }
                buf.append(t).append("\n");
            }
        }
        if(buf!=null&&ticket>0&&qnum>0)qb.add(ticket,qnum,buf.toString(),answers);
        return qb;
    }

    int size(){ return entries.size(); }

    Match best(String recognized){
        Set<String> q=tokens(recognized);
        Entry best=null;
        double bestScore=0, second=0;
        for(Entry e:entries){
            double s=weightedScore(q,tokens(e.block),recognized,e.block);
            if(s>bestScore){
                second=bestScore;
                bestScore=s;
                best=e;
            }else if(s>second){
                second=s;
            }
        }
        return new Match(best,bestScore,second);
    }

    private void add(int ticket,int qnum,String block,Map<Integer,int[]> answers){
        String clean=block.trim();
        if(clean.length()<12)return;
        int idx=0;
        int[] row=answers.get(ticket);
        if(row!=null&&qnum>=1&&qnum<=row.length)idx=row[qnum-1];
        String correct=extractOption(clean,idx);
        entries.add(new Entry(ticket,qnum,clean,idx,correct));
    }

    private static Map<Integer,int[]> parseAnswerTable(String text){
        HashMap<Integer,int[]> out=new HashMap<>();
        Pattern row=Pattern.compile("(?m)^\\s*Билет\\s*№\\s*(\\d+)\\s+((?:[1-4]\\s+){19}[1-4])\\s*$",
            Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE);
        Matcher m=row.matcher(text);
        while(m.find()){
            int ticket=Integer.parseInt(m.group(1));
            String[] nums=m.group(2).trim().split("\\s+");
            if(nums.length!=20)continue;
            int[] a=new int[20];
            boolean ok=true;
            for(int i=0;i<20;i++){
                try{a[i]=Integer.parseInt(nums[i]);}
                catch(Exception e){ok=false;break;}
            }
            if(ok)out.put(ticket,a);
        }
        return out;
    }

    private static String extractOption(String block,int idx){
        if(idx<=0)return "";
        String[] lines=block.split("\n");
        Pattern p=Pattern.compile("^\\s*"+idx+"[\\.\\)]\\s*(.*)$");
        StringBuilder ans=new StringBuilder();
        boolean collecting=false;
        for(String line:lines){
            Matcher m=p.matcher(line.trim());
            if(m.matches()){
                collecting=true;
                ans.append(m.group(1).trim());
                continue;
            }
            if(collecting){
                if(line.trim().matches("^[1-4][\\.\\)].*"))break;
                String t=line.trim();
                if(t.isEmpty()||t.equalsIgnoreCase("Варианты ответа:"))break;
                if(ans.length()>0)ans.append(' ');
                ans.append(t);
            }
        }
        return ans.toString().trim();
    }

    private static Set<String> tokens(String s){
        String n=s==null?"":s.toLowerCase(Locale.ROOT)
            .replace('ё','е')
            .replaceAll("[^\\p{L}\\p{N}]+"," ")
            .trim();
        HashSet<String> out=new HashSet<>();
        if(n.isEmpty())return out;
        for(String w:n.split("\\s+")){
            if(w.length()>=2&&!STOP.contains(w))out.add(w);
        }
        return out;
    }

    private static double weightedScore(Set<String>a,Set<String>b,String rawA,String rawB){
        if(a.isEmpty()||b.isEmpty())return 0;
        int inter=0;
        for(String x:a)if(b.contains(x))inter++;
        double containment=(double)inter/Math.max(1,a.size());
        int union=a.size()+b.size()-inter;
        double jaccard=(double)inter/Math.max(1,union);

        String na=normalize(rawA), nb=normalize(rawB);
        double phrase=0;
        if(na.length()>=24&&nb.contains(firstWords(na,8)))phrase=1.0;
        else if(nb.length()>=24&&na.contains(firstWords(nb,8)))phrase=0.8;

        return 0.58*containment + 0.32*jaccard + 0.10*phrase;
    }

    private static String normalize(String s){
        return (s==null?"":s.toLowerCase(Locale.ROOT).replace('ё','е')
            .replaceAll("[^\\p{L}\\p{N}]+"," ").replaceAll("\\s+"," ").trim());
    }

    private static String firstWords(String s,int n){
        String[] p=s.split(" ");
        StringBuilder b=new StringBuilder();
        for(int i=0;i<Math.min(n,p.length);i++){
            if(i>0)b.append(' ');
            b.append(p[i]);
        }
        return b.toString();
    }

    private static final Set<String> STOP=new HashSet<>(Arrays.asList(
        "варианты","ответа","вопрос","можно","вам","вы","если","при","для","или","на","по","из","и","в","с","не","что","как"
    ));
}
