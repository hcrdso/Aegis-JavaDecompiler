package dev.aegis.analysis;

import dev.aegis.classfile.*;
import dev.aegis.util.JavaNames;
import dev.aegis.workspace.*;
import java.util.*;

/** Detects broad obfuscation families/patterns without depending on vendor-specific markers. */
public final class ObfuscationFingerprint {
    public record Result(int retroGuardStyleScore, int keywordIdentifiers, int shortIdentifiers,
                         int overloadedShortNames, int classesWithoutDebug, List<String> notes) {}

    public Result inspect(Workspace workspace) {
        int keywords=0,shorts=0,overloads=0,noDebug=0,total=0;
        ArrayList<String> notes=new ArrayList<>();
        for(ClassUnit u:workspace.classes()){
            ClassFile cf;try{cf=ClassFileParser.parse(u.bytes());}catch(RuntimeException ex){continue;}total++;
            if(!DebugMetadata.hasDebugInfo(cf))noDebug++;
            if(JavaNames.isKeyword(simple(cf.thisClass())))keywords++;
            Map<String,Integer> methodNameCounts=new HashMap<>();
            for(MemberInfo f:cf.fields()){
                if(JavaNames.isKeyword(f.name()))keywords++;
                if(f.name().length()<=2)shorts++;
            }
            for(MemberInfo m:cf.methods())if(!m.name().startsWith("<")){
                if(JavaNames.isKeyword(m.name()))keywords++;
                if(m.name().length()<=2)shorts++;
                methodNameCounts.merge(m.name(),1,Integer::sum);
            }
            for(var e:methodNameCounts.entrySet())if(e.getValue()>=3&&(e.getKey().length()<=2||JavaNames.isKeyword(e.getKey())))overloads++;
        }
        int score=0;
        if(keywords>0){score+=45;notes.add("JVM identifiers use Java keywords (classic RetroGuard/yGuard-style capability)");}
        if(shorts>Math.max(5,total*3)){score+=20;notes.add("dense one/two-character identifier namespace");}
        if(overloads>0){score+=20;notes.add("heavy reuse/overloading of short method names across descriptors");}
        if(total>0&&noDebug*100/total>=70){score+=15;notes.add("most SourceFile/LVT/LNT debug metadata is stripped");}
        return new Result(Math.min(100,score),keywords,shorts,overloads,noDebug,List.copyOf(notes));
    }

    private static String simple(String n){int p=Math.max(n.lastIndexOf('/'),n.lastIndexOf('$'));return p<0?n:n.substring(p+1);}
}
