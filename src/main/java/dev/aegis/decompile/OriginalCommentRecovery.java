package dev.aegis.decompile;

import java.util.*;
import java.util.regex.*;

/** Extracts comments from an actual Java source file when one is available beside the class. */
final class OriginalCommentRecovery {
    enum Kind { LINE, BLOCK, JAVADOC }
    record SourceComment(int startLine,int endLine,String text,Kind kind) {}

    static List<SourceComment> parse(String source){
        if(source==null||source.isEmpty())return List.of();
        ArrayList<SourceComment> out=new ArrayList<>();int n=source.length(),line=1,i=0;
        while(i<n){char c=source.charAt(i),d=i+1<n?source.charAt(i+1):0;
            if(c=='"'){ // string or text block
                if(i+2<n&&source.charAt(i+1)=='"'&&source.charAt(i+2)=='"'){i+=3;while(i+2<n&&!(source.charAt(i)=='"'&&source.charAt(i+1)=='"'&&source.charAt(i+2)=='"')){if(source.charAt(i++)=='\n')line++;}i=Math.min(n,i+3);continue;}
                i++;while(i<n){char x=source.charAt(i++);if(x=='\n')line++;if(x=='\\'&&i<n){if(source.charAt(i++)=='\n')line++;continue;}if(x=='"')break;}continue;
            }
            if(c=='\''){i++;while(i<n){char x=source.charAt(i++);if(x=='\n')line++;if(x=='\\'&&i<n){if(source.charAt(i++)=='\n')line++;continue;}if(x=='\'')break;}continue;}
            if(c=='/'&&d=='/'){int sl=line,st=i;i+=2;while(i<n&&source.charAt(i)!='\n')i++;out.add(new SourceComment(sl,line,source.substring(st,i).trim(),Kind.LINE));continue;}
            if(c=='/'&&d=='*'){int sl=line,st=i;boolean jd=i+2<n&&source.charAt(i+2)=='*';i+=2;while(i+1<n&&!(source.charAt(i)=='*'&&source.charAt(i+1)=='/')){if(source.charAt(i++)=='\n')line++;}i=Math.min(n,i+2);out.add(new SourceComment(sl,line,source.substring(st,i).trim(),jd?Kind.JAVADOC:Kind.BLOCK));continue;}
            if(c=='\n')line++;i++;
        }
        return List.copyOf(out);
    }

    static List<SourceComment> forType(String source,String simpleName){
        if(source==null||simpleName==null||simpleName.isBlank())return List.of();
        String target=simpleName;int dollar=target.lastIndexOf('$');if(dollar>=0&&dollar+1<target.length())target=target.substring(dollar+1);
        String[] lines=source.split("\\R",-1);
        Pattern p=Pattern.compile("(?:@interface|class|interface|enum|record)\\s+"+Pattern.quote(target)+"\\b");
        int decl=-1;for(int i=0;i<lines.length;i++)if(p.matcher(stripLineStrings(lines[i])).find()){decl=i+1;break;}
        if(decl<0)return List.of();
        return precedingComments(source,decl,8,true);
    }

    static List<SourceComment> forMethod(String source,int minLine,int maxLine){
        if(source==null||minLine<=0)return List.of();
        LinkedHashMap<String,SourceComment> out=new LinkedHashMap<>();
        for(SourceComment c:parse(source)) if(c.startLine()<=maxLine&&c.endLine()>=minLine) out.put(key(c),c);
        for(SourceComment c:precedingComments(source,minLine,6,true)) out.putIfAbsent(key(c),c);
        ArrayList<SourceComment> list=new ArrayList<>(out.values());list.sort(Comparator.comparingInt(SourceComment::startLine));return List.copyOf(list);
    }

    private static List<SourceComment> precedingComments(String source,int targetLine,int maxGap,boolean preferJavadoc){
        ArrayList<SourceComment> all=new ArrayList<>(parse(source)),out=new ArrayList<>();
        String[] lines=source.split("\\R",-1);
        for(SourceComment c:all){
            if(c.endLine()>=targetLine)continue;
            int gap=targetLine-c.endLine();if(gap>maxGap)continue;
            boolean safe=true;
            for(int line=c.endLine()+1;line<targetLine&&line<=lines.length;line++){
                String x=lines[line-1].trim();
                if(x.isEmpty()||x.startsWith("@"))continue;
                // A declaration signature between the comment and first executable line is expected.
                if(x.contains(";")&&!x.contains("throws ")){safe=false;break;}
                if(x.equals("}")){safe=false;break;}
            }
            if(safe&&(c.kind()==Kind.JAVADOC||gap<=3||!preferJavadoc))out.add(c);
        }
        if(!out.isEmpty()){
            int latest=out.stream().mapToInt(SourceComment::endLine).max().orElse(-1);
            out.removeIf(c->latest-c.endLine()>1);
        }
        out.sort(Comparator.comparingInt(SourceComment::startLine));return List.copyOf(out);
    }

    static String asTaggedLine(SourceComment c){
        String t=c.text().replace("\r","").replace("\n"," ").replaceAll("\\s+"," ").trim();
        if(t.length()>220)t=t.substring(0,217)+"...";
        String kind=switch(c.kind()){case JAVADOC->"javadoc";case BLOCK->"block comment";case LINE->"line comment";};
        return "// [recovered original "+kind+" lines "+c.startLine()+"-"+c.endLine()+"] "+t;
    }

    private static String key(SourceComment c){return c.startLine()+":"+c.endLine()+":"+c.text();}
    private static String stripLineStrings(String line){
        StringBuilder b=new StringBuilder(line.length());boolean string=false,chr=false,esc=false;
        for(int i=0;i<line.length();i++){char c=line.charAt(i);if(esc){esc=false;b.append(' ');continue;}if((string||chr)&&c=='\\'){esc=true;b.append(' ');continue;}if(!chr&&c=='"'){string=!string;b.append(' ');continue;}if(!string&&c=='\''){chr=!chr;b.append(' ');continue;}b.append(string||chr?' ':c);}return b.toString();
    }
}
