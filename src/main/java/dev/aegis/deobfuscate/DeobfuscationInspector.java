package dev.aegis.deobfuscate;

import dev.aegis.cfg.ControlFlowGraph;
import dev.aegis.classfile.*;
import java.util.*;

public final class DeobfuscationInspector {
    public String inspect(byte[] bytes) {
        ClassFile cf=ClassFileParser.parse(bytes);
        StringBuilder b=new StringBuilder();
        b.append("Aegis Deobfuscator\n");
        b.append("Class: ").append(cf.thisClass().replace('/','.')).append("\n");
        b.append("Classfile: ").append(cf.majorVersion()).append(" / Java ").append(cf.javaVersion()).append("\n\n");
        int methods=0,total=0,opaque=0,dead=0,stores=0,loops=0,irreducible=0,postdominated=0,suspiciousHandlers=0;
        for(MemberInfo m:cf.methods()){
            AttributeInfo ca=m.attribute("Code");
            if(ca==null)continue;
            methods++;
            try{
                CodeAttribute code=CodeAttribute.parse(ca,cf.constantPool());
                DeobfuscationResult r=new Deobfuscator().analyze(cf,m,code);
                ControlFlowGraph cfg=ControlFlowGraph.build(r.instructions(),code);
                int l=cfg.naturalLoops().size();
                int irr=cfg.irreducibleRegions().size();
                int joins=0;
                for(ControlFlowGraph.BasicBlock block:cfg.blocks())if(cfg.immediatePostDominator(block)!=null)joins++;
                loops+=l;
                irreducible+=irr;
                postdominated+=joins;
                int suspicious=suspiciousHandlers(code);
                suspiciousHandlers+=suspicious;
                total+=r.report().totalSimplifications();
                opaque+=r.report().opaquePredicates();
                dead+=r.report().unreachableInstructions();
                stores+=r.report().deadStores();
                b.append(m.name()).append(m.descriptor()).append('\n');
                b.append("  blocks: ").append(cfg.blocks().size()).append("  loops: ").append(l).append("  joins: ").append(joins).append("  handlers: ").append(code.exceptionTable().size()).append('\n');
                if(irr>0)b.append("  irreducible regions: ").append(irr).append('\n');
                if(suspicious>0)b.append("  suspicious throw handlers: ").append(suspicious).append('\n');
                b.append("  ").append(r.report().compact()).append('\n');
                for(String n:r.report().notes())b.append("  - ").append(n).append('\n');
                b.append('\n');
            }catch(RuntimeException ex){
                b.append(m.name()).append(m.descriptor()).append("\n  analysis failed: ").append(ex.getMessage()).append("\n\n");
            }
        }
        int at=b.indexOf("\n\n")+2;
        b.insert(at,"Summary: methods="+methods+", simplifications="+total+", opaque="+opaque+", dead="+dead+", deadStores="+stores+", loops="+loops+", joins="+postdominated+", irreducible="+irreducible+", suspiciousHandlers="+suspiciousHandlers+"\n\n");
        b.append("Static string decryptor: enabled\n");
        b.append("Bytecode export cleaner: conservative rewrites\n");
        return b.toString();
    }

    private static int suspiciousHandlers(CodeAttribute code){
        List<Instruction> xs;
        try{xs=BytecodeDecoder.decode(code.code());}catch(RuntimeException ex){return 0;}
        Map<Integer,Integer> byOffset=new HashMap<>();
        for(int i=0;i<xs.size();i++)byOffset.put(xs.get(i).offset(),i);
        int count=0;
        for(CodeAttribute.ExceptionHandler h:code.exceptionTable()){
            Integer start=byOffset.get(h.handlerPc());
            if(start==null)continue;
            int i=start;
            while(i<xs.size()&&xs.get(i).opcode()==0)i++;
            if(i>=xs.size())continue;
            if(xs.get(i).opcode()==191){count++;continue;}
            if(isAStore(xs.get(i).opcode()))i++;
            while(i<xs.size()&&xs.get(i).opcode()==0)i++;
            if(i>=xs.size()||xs.get(i).opcode()!=184)continue;
            int limit=Math.min(xs.size(),i+4);
            boolean throwSeen=false;
            for(int j=i+1;j<limit;j++){
                int op=xs.get(j).opcode();
                if(op==191){throwSeen=true;break;}
                if(op==0||op==87||op==25||(op>=42&&op<=45))continue;
                break;
            }
            if(throwSeen)count++;
        }
        return count;
    }

    private static boolean isAStore(int op){return op==58||(op>=75&&op<=78);}
}
