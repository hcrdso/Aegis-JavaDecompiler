package dev.aegis.rename;

import dev.aegis.classfile.*;
import java.util.*;

/**
 * Conservative whole-workspace scanner for reflection/name-sensitive APIs.
 *
 * The goal is not to "solve" reflection. It identifies names that must be
 * reserved by the renamer and marks dynamic lookups as unsafe in Safe mode.
 */
public final class ReflectionUsageAnalyzer {
    public record Result(Set<String> memberNames, Set<String> classNames, boolean dynamicReflection, List<String> notes) {}

    public Result scan(Collection<ClassFile> classes) {
        LinkedHashSet<String> members=new LinkedHashSet<>(), names=new LinkedHashSet<>();
        ArrayList<String> notes=new ArrayList<>();
        boolean dynamic=false;
        for(ClassFile cf:classes) for(MemberInfo m:cf.methods()) {
            AttributeInfo ca=m.attribute("Code"); if(ca==null)continue;
            try {
                CodeAttribute code=CodeAttribute.parse(ca,cf.constantPool());
                List<Instruction> xs=BytecodeDecoder.decode(code.code());
                for(int ix=0;ix<xs.size();ix++){
                    Instruction in=xs.get(ix); int op=in.opcode();
                    if(op<182||op>185)continue; // virtual/special/static/interface
                    ConstantPool.MemberRef r=cf.constantPool().memberRef(in.u2(0));
                    boolean classLookup=r.owner().equals("java/lang/Class") && r.name().equals("forName");
                    boolean memberLookup=r.owner().equals("java/lang/Class") && Set.of(
                            "getMethod","getDeclaredMethod","getField","getDeclaredField").contains(r.name());
                    boolean mhLookup=r.owner().equals("java/lang/invoke/MethodHandles$Lookup") && (
                            r.name().startsWith("find") || r.name().startsWith("unreflect"));
                    boolean serializationLookup=r.owner().equals("java/io/ObjectStreamClass") && r.name().equals("lookup");
                    if(!(classLookup||memberLookup||mhLookup||serializationLookup))continue;

                    String literal=nearestStringLiteral(cf,xs,ix,16);
                    if(classLookup){
                        if(literal!=null)names.add(literal.replace('.','/')); else dynamic=true;
                    } else if(memberLookup||mhLookup){
                        if(literal!=null)members.add(literal); else dynamic=true;
                    } else dynamic=true;
                    notes.add(cf.thisClass()+"."+m.name()+m.descriptor()+" uses name-sensitive API: "+r.owner()+"."+r.name()
                            +(literal==null?" (dynamic)":" (literal=\""+literal+"\")"));
                }
            } catch(RuntimeException ignored) { }
        }
        return new Result(Set.copyOf(members),Set.copyOf(names),dynamic,List.copyOf(notes));
    }

    /** Search backwards through argument-construction noise for the closest String constant. */
    private static String nearestStringLiteral(ClassFile cf,List<Instruction> xs,int invokeIndex,int budget){
        int seen=0;
        for(int i=invokeIndex-1;i>=0&&seen<budget;i--,seen++){
            Instruction in=xs.get(i); int op=in.opcode();
            if(op==18||op==19){
                try{Object v=cf.constantPool().constant(op==18?in.u1(0):in.u2(0));if(v instanceof String s)return s;}catch(RuntimeException ignored){}
            }
            // Crossing a control-flow boundary makes a guessed literal too risky.
            if((op>=153&&op<=171)||op==167||op==200||op>=172&&op<=177||op==191)break;
        }
        return null;
    }
}
