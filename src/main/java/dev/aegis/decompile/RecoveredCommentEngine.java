package dev.aegis.decompile;

import dev.aegis.classfile.*;
import dev.aegis.rename.*;
import java.util.*;

/**
 * Produces explicitly-labelled comments from surviving metadata and bytecode semantics.
 * These are NOT claimed to be original source comments.
 */
public final class RecoveredCommentEngine {
    public record Comment(String text, int confidence) {}

    public List<Comment> infer(ClassFile cf, MemberInfo method, MappingSet mappings) {
        if(method.name().startsWith("<")) return specialInitializerComments(cf,method);
        LinkedHashMap<String,Comment> out=new LinkedHashMap<>();
        try {
            AttributeInfo a=method.attribute("Code"); if(a==null)return List.of();
            CodeAttribute code=CodeAttribute.parse(a,cf.constantPool());
            List<Instruction> xs=BytecodeDecoder.decode(code.code());
            int reads=0,writes=0,calls=0,alloc=0,throwsCount=0,branches=0,loops=0;
            ArrayList<String> stringLiterals=new ArrayList<>();
            for(Instruction i:xs){
                int op=i.opcode();
                if(op==178||op==180)reads++; if(op==179||op==181)writes++;
                if(op>=182&&op<=186)calls++; if(op==187||op==188||op==189||op==197)alloc++;
                if(op==191)throwsCount++; if((op>=153&&op<=171)||op==167||op==200)branches++;
                for(int t:i.branchTargets()) if(t<=i.offset())loops++;
                if(op==18||op==19){try{Object v=cf.constantPool().constant(op==18?i.u1(0):i.u2(0));if(v instanceof String s&&s.length()>=4)stringLiterals.add(s);}catch(RuntimeException ignored){}}
            }
            MappingSet.Decision d=mappings.methodDecision(cf.thisClass(),method.name(),method.descriptor());
            String mapped=d==null?method.name():d.target();
            if(d!=null&&d.confidence()>=70) put(out,"Likely purpose: "+humanize(mapped)+".",Math.min(94,d.confidence()));
            if(loops>0) put(out,"Contains "+(loops==1?"a loop":"looping control flow")+" reconstructed from backward branches.",78);
            if(throwsCount>0) put(out,"Performs validation/error propagation and may throw on failure.",66);
            if(writes>0&&reads>0) put(out,"Reads and updates object/class state.",58);
            else if(writes>0) put(out,"Updates object/class state.",60);
            if(alloc>0&&calls>0) put(out,"Builds one or more objects before invoking downstream operations.",54);
            String diagnostic=bestDiagnostic(stringLiterals);
            if(diagnostic!=null) put(out,"Observed diagnostic text: \""+shorten(diagnostic)+"\".",88);
            if(diagnostic==null&&branches==0&&calls==1&&writes==0&&alloc==0) put(out,"Thin wrapper/delegation method.",75);
        } catch(RuntimeException ignored) { }
        return List.copyOf(out.values());
    }

    private static List<Comment> specialInitializerComments(ClassFile cf,MemberInfo m){
        if(!m.name().equals("<clinit>"))return List.of();
        try{
            CodeAttribute code=CodeAttribute.parse(m.attribute("Code"),cf.constantPool());
            for(Instruction i:BytecodeDecoder.decode(code.code())) if(i.opcode()==179){
                ConstantPool.MemberRef r=cf.constantPool().memberRef(i.u2(0));
                if(r.owner().equals(cf.thisClass()))return List.of(new Comment("Initializes static field "+r.name()+".",82));
            }
        }catch(RuntimeException ignored){}
        return List.of();
    }

    private static void put(Map<String,Comment> out,String text,int confidence){out.putIfAbsent(text,new Comment(text,confidence));}
    private static String bestDiagnostic(List<String> ss){
        String best=null;for(String s:ss){String l=s.toLowerCase(Locale.ROOT);if(l.contains("fail")||l.contains("error")||l.contains("warn")||l.contains("unable")||l.contains("invalid")||l.contains("missing")){if(best==null||s.length()>best.length())best=s;}}return best;
    }
    private static String humanize(String s){
        if(s==null||s.isBlank())return "perform this operation";
        return s.replaceAll("([a-z0-9])([A-Z])","$1 $2").replace('_',' ').toLowerCase(Locale.ROOT);
    }
    private static String shorten(String s){String x=s.replace('\n',' ').replace('\r',' ');return x.length()>90?x.substring(0,87)+"...":x;}
}
