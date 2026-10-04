package dev.aegis.decompile;

import java.util.*;

/** Extracts comments from an actual Java source file when one is available beside the class. */
final class OriginalCommentRecovery {
    record SourceComment(int startLine,int endLine,String text) {}

    static List<SourceComment> parse(String source){
        if(source==null||source.isEmpty())return List.of();
        ArrayList<SourceComment> out=new ArrayList<>();int n=source.length(),line=1,i=0;
        while(i<n){char c=source.charAt(i),d=i+1<n?source.charAt(i+1):0;
            if(c=='"'){ // string or text block
                if(i+2<n&&source.charAt(i+1)=='"'&&source.charAt(i+2)=='"'){i+=3;while(i+2<n&&!(source.charAt(i)=='"'&&source.charAt(i+1)=='"'&&source.charAt(i+2)=='"')){if(source.charAt(i++)=='\n')line++;}i=Math.min(n,i+3);continue;}
                i++;while(i<n){char x=source.charAt(i++);if(x=='\n')line++;if(x=='\\'&&i<n){if(source.charAt(i++)=='\n')line++;continue;}if(x=='"')break;}continue;
            }
            if(c=='\''){i++;while(i<n){char x=source.charAt(i++);if(x=='\n')line++;if(x=='\\'&&i<n){if(source.charAt(i++)=='\n')line++;continue;}if(x=='\'')break;}continue;}
            if(c=='/'&&d=='/'){int sl=line,st=i;i+=2;while(i<n&&source.charAt(i)!='\n')i++;out.add(new SourceComment(sl,line,source.substring(st,i).trim()));continue;}
            if(c=='/'&&d=='*'){int sl=line,st=i;i+=2;while(i+1<n&&!(source.charAt(i)=='*'&&source.charAt(i+1)=='/')){if(source.charAt(i++)=='\n')line++;}i=Math.min(n,i+2);out.add(new SourceComment(sl,line,source.substring(st,i).trim()));continue;}
            if(c=='\n')line++;i++;
        }
        return List.copyOf(out);
    }

    static List<SourceComment> forMethod(String source,int minLine,int maxLine){
        if(source==null||minLine<=0)return List.of();ArrayList<SourceComment> all=new ArrayList<>(parse(source)),out=new ArrayList<>();
        for(SourceComment c:all) if ((c.startLine()<=maxLine&&c.endLine()>=minLine) || (c.endLine()<minLine&&minLine-c.endLine()<=4)) out.add(c);
        out.sort(Comparator.comparingInt(SourceComment::startLine));return List.copyOf(out);
    }

    static String asTaggedLine(SourceComment c){
        String t=c.text().replace("\r","").replace("\n"," ").replaceAll("\\s+"," ").trim();
        if(t.length()>180)t=t.substring(0,177)+"...";
        return "// [recovered original comment lines "+c.startLine()+"-"+c.endLine()+"] "+t;
    }
}
