package dev.aegis.deobfuscate;

import dev.aegis.cfg.ControlFlowGraph;
import dev.aegis.classfile.*;
import java.util.*;

public final class DeobfuscationInspector {
    public String inspect(byte[] bytes) {
        ClassFile cf=ClassFileParser.parse(bytes);StringBuilder b=new StringBuilder();
        b.append("Aegis Deobfuscator\n");
        b.append("Class: ").append(cf.thisClass().replace('/','.')).append("\n");
        b.append("Classfile: ").append(cf.majorVersion()).append(" / Java ").append(cf.javaVersion()).append("\n\n");
        int methods=0,total=0,opaque=0,dead=0,stores=0,loops=0;
        for(MemberInfo m:cf.methods()){
            AttributeInfo ca=m.attribute("Code");if(ca==null)continue;methods++;
            try{
                CodeAttribute code=CodeAttribute.parse(ca,cf.constantPool());
                DeobfuscationResult r=new Deobfuscator().analyze(cf,m,code);
                ControlFlowGraph cfg=ControlFlowGraph.build(r.instructions(),code);int l=cfg.naturalLoops().size();
                loops+=l;total+=r.report().totalSimplifications();opaque+=r.report().opaquePredicates();dead+=r.report().unreachableInstructions();stores+=r.report().deadStores();
                b.append(m.name()).append(m.descriptor()).append('\n');
                b.append("  blocks: ").append(cfg.blocks().size()).append("  loops: ").append(l).append("  handlers: ").append(code.exceptionTable().size()).append('\n');
                b.append("  ").append(r.report().compact()).append('\n');
                for(String n:r.report().notes())b.append("  - ").append(n).append('\n');
                b.append('\n');
            }catch(RuntimeException ex){b.append(m.name()).append(m.descriptor()).append("\n  analysis failed: ").append(ex.getMessage()).append("\n\n");}
        }
        b.insert(b.indexOf("\n\n")+2,"Summary: methods="+methods+", simplifications="+total+", opaque="+opaque+", dead="+dead+", deadStores="+stores+", loops="+loops+"\n\n");
        b.append("Static string decryptor: enabled (sandboxed JVM subset; constant call-sites only)\n");
        b.append("Bytecode export cleaner: conservative length-preserving rewrites only\n");
        return b.toString();
    }
}
