package dev.aegis.decompile;

import dev.aegis.cfg.ControlFlowGraph;
import dev.aegis.classfile.*;
import dev.aegis.deobfuscate.*;
import dev.aegis.rename.MappingSet;
import dev.aegis.rename.SemanticNameEngine;
import dev.aegis.util.JavaNames;
import java.util.*;

/** Native structured JVM bytecode -> Java source emitter. */
final class MethodBodyDecompiler {
    record Result(List<String> lines, boolean complete, boolean hasTerminalReturn) {}
    private record Expr(String text, String type, Object constant, int width, String newType) {
        static Expr of(String text, String type) { return new Expr(text,type,null,widthOf(type),null); }
        static Expr constant(String text,String type,Object value){return new Expr(text,type,value,widthOf(type),null);}
        static Expr fresh(String type){return new Expr("__new__("+type+")",type,null,1,type);}
        private static int widthOf(String t){return "long".equals(t)||"double".equals(t)?2:1;}
    }
    private static final class State {
        final ArrayList<Expr> stack = new ArrayList<>();
        final HashMap<Integer,Expr> aliases = new HashMap<>();
        State copy(){State s=new State();s.stack.addAll(stack);s.aliases.putAll(aliases);return s;}
        void push(Expr e){stack.add(e);} Expr pop(){if(stack.isEmpty())return Expr.of("/* stack-underflow */ null","java.lang.Object");return stack.remove(stack.size()-1);} Expr peek(){return stack.isEmpty()?Expr.of("null","java.lang.Object"):stack.get(stack.size()-1);}
    }

    private final ClassFile cf; private final MemberInfo method; private final CodeAttribute code; private final MappingSet map;
    private final DeobfuscationResult deobf; private final List<Instruction> insns; private final Map<Integer,Integer> indexByOffset=new HashMap<>();
    private final LocalVariableTable lvt; private final BootstrapMethods bootstraps; private final ConstantMethodEvaluator constantEvaluator = new ConstantMethodEvaluator();
    private final HashMap<Integer,String> localNames=new HashMap<>(); private final HashMap<Integer,String> localTypes=new HashMap<>();
    private final HashSet<Integer> parameterSlots=new HashSet<>(); private final ArrayList<String> lines=new ArrayList<>();
    private boolean terminalReturn; private boolean complete=true; private int syntheticArrayId;

    private MethodBodyDecompiler(ClassFile cf,MemberInfo method,CodeAttribute code,MappingSet map,List<String> params){
        this.cf=cf;this.method=method;this.code=code;this.map=map;this.lvt=LocalVariableTable.from(code,cf.constantPool());this.bootstraps=BootstrapMethods.from(cf);
        this.deobf=new Deobfuscator().analyze(cf,method,code);this.insns=deobf.instructions();for(int i=0;i<insns.size();i++)indexByOffset.put(insns.get(i).offset(),i);
        initLocals(params); inferLocals();
    }
    static Result decompile(ClassFile cf,MemberInfo method,CodeAttribute code,MappingSet map,List<String> params){return new MethodBodyDecompiler(cf,method,code,map,params).run();}

    private Result run(){
        try {
            if(deobf.report().totalSimplifications()>0) lines.add("// Aegis deobfuscator: "+deobf.report().compact());
            emitLocalDeclarations();
            State state=new State();
            if(tryBooleanReturnChain(0,insns.size(),state)) return new Result(List.copyOf(lines),true,true);
            if(!code.exceptionTable().isEmpty() && tryEmitSimpleExceptionRegion(state)) {
                return new Result(List.copyOf(lines),complete,terminalReturn);
            }
            emitRange(0,code.code().length,state,0,new HashSet<>());
            return new Result(List.copyOf(lines),complete,terminalReturn);
        } catch(RuntimeException ex){
            complete=false; lines.add("// Aegis structured emitter recovered from: "+safe(ex.getMessage()));
            lines.add("// Remaining bytecode is available in the Bytecode tab; no fake return value was inserted here.");
            return new Result(List.copyOf(lines),false,terminalReturn);
        }
    }

    private void initLocals(List<String> params){
        DescriptorParser.MethodDescriptor md=DescriptorParser.method(method.descriptor());int slot=0;
        if(!AccessFlags.has(method.accessFlags(),AccessFlags.STATIC)){localNames.put(0,"this");localTypes.put(0,SourceDecompiler.javaClass(cf.thisClass(),map));parameterSlots.add(0);slot=1;}
        for(int i=0;i<md.parameterTypes().size();i++){String n=i<params.size()?params.get(i):"param"+i;LocalVariableTable.Local lv=lvt.find(slot,0);if(lv!=null&&!"this".equals(lv.name()))n=JavaNames.sanitize(lv.name(),n);localNames.put(slot,n);localTypes.put(slot,map.mapJavaType(md.parameterTypes().get(i)));parameterSlots.add(slot);slot+=md.slotWidths().get(i);}
    }
    private void inferLocals(){
        for(int i=0;i<insns.size();i++){
            Instruction in=insns.get(i);Integer slot=storeSlot(in);if(slot==null||parameterSlots.contains(slot))continue;
            LocalVariableTable.Local lv=lvt.find(slot,in.offset());if(lv!=null){localNames.putIfAbsent(slot,JavaNames.sanitize(lv.name(),"local"+slot));try{localTypes.putIfAbsent(slot,map.mapJavaType(DescriptorParser.fieldType(lv.descriptor())));}catch(RuntimeException ignored){}}
            localNames.putIfAbsent(slot,"local"+slot);localTypes.putIfAbsent(slot,inferStoreType(i,in));
        }
    }
    private String inferStoreType(int index,Instruction store){
        int op=store.opcode();if((op>=54&&op<=54)||(op>=59&&op<=62))return "int";if(op==55||(op>=63&&op<=66))return "long";if(op==56||(op>=67&&op<=70))return "float";if(op==57||(op>=71&&op<=74))return "double";
        if(index>0){Instruction p=insns.get(index-1);try{return switch(p.opcode()){
            case 18,19,20 -> typeOfConstant(cf.constantPool().constant(p.opcode()==18?p.u1(0):p.u2(0)));
            case 187 -> SourceDecompiler.javaClass(cf.constantPool().className(p.u2(0)),map);
            case 189 -> SourceDecompiler.javaClass(cf.constantPool().className(p.u2(0)),map)+"[]";
            case 192 -> SourceDecompiler.javaClass(cf.constantPool().className(p.u2(0)),map);
            case 178,180 -> map.mapJavaType(DescriptorParser.fieldType(cf.constantPool().memberRef(p.u2(0)).descriptor()));
            case 182,183,184,185 -> map.mapJavaType(DescriptorParser.method(cf.constantPool().memberRef(p.u2(0)).descriptor()).returnType());
            case 186 -> map.mapJavaType(DescriptorParser.method(cf.constantPool().dynamicRef(p.u2(0)).descriptor()).returnType());
            default -> "java.lang.Object";};}catch(RuntimeException ignored){}
        }return "java.lang.Object";
    }
    private void emitLocalDeclarations(){
        ArrayList<Integer> slots=new ArrayList<>(localNames.keySet());Collections.sort(slots);boolean any=false;
        for(int slot:slots){if(parameterSlots.contains(slot))continue;String t=localTypes.getOrDefault(slot,"java.lang.Object");if("void".equals(t))t="java.lang.Object";add(0,t+" "+localNames.get(slot)+";");any=true;}
        if(any)add(0,"");
    }

    private int emitRange(int startIndex,int endOffset,State state,int depth,Set<Integer> activeLoops){
        int i=startIndex;
        while(i<insns.size()){
            Instruction in=insns.get(i);if(in.offset()>=endOffset)return i;if(deobf.isUnreachable(in.offset())||in.opcode()==0){i++;continue;}
            if(ControlFlowGraph.isConditional(in.opcode())){
                Expr branchCond=branchCondition(in,state);Boolean forced=deobf.forcedBranch(in.offset());
                if(forced!=null){i=forced?indexOf(in.branchTargets()[0]):i+1;continue;}
                int target=in.branchTargets()[0];
                if(target>in.offset()){
                    int back=findLoopBackedge(i+1,target,in.offset());
                    if(back>=0&&!activeLoops.contains(in.offset())){
                        String cond=negate(branchCond.text());add(depth,"while ("+cond+") {");Set<Integer> nested=new HashSet<>(activeLoops);nested.add(in.offset());
                        emitRange(i+1,insns.get(back).offset(),state.copy(),depth+1,nested);add(depth,"}");i=indexOf(target);continue;
                    }
                    int gotoBefore=previousLiveIndex(target);
                    if(gotoBefore>=i+1&&ControlFlowGraph.isGoto(insns.get(gotoBefore).opcode())){
                        int join=threadedTarget(insns.get(gotoBefore));
                        if(join>target){
                            Expr ternary=tryPureTernary(i+1,insns.get(gotoBefore).offset(),target,join,state,branchCond);
                            if(ternary!=null){state.push(ternary);i=indexOf(join);continue;}
                            add(depth,"if ("+negate(branchCond.text())+") {");State a=state.copy();emitRange(i+1,insns.get(gotoBefore).offset(),a,depth+1,activeLoops);add(depth,"} else {");State b=state.copy();emitRange(indexOf(target),join,b,depth+1,activeLoops);add(depth,"}");mergeStacks(state,a,b);i=indexOf(join);continue;
                        }
                    }
                    add(depth,"if ("+negate(branchCond.text())+") {");State body=state.copy();emitRange(i+1,target,body,depth+1,activeLoops);add(depth,"}");mergeStacksConservative(state,body);i=indexOf(target);continue;
                } else {
                    add(depth,"if ("+branchCond.text()+") { continue; }");i++;continue;
                }
            }
            if(ControlFlowGraph.isGoto(in.opcode())){
                int target=threadedTarget(in);if(target<=in.offset()){add(depth,"continue;");return i+1;}if(target>=endOffset)return indexOf(target);i=indexOf(target);continue;
            }
            if(in.opcode()==170||in.opcode()==171){Integer forcedSwitch=deobf.forcedSwitchTarget(in.offset());if(forcedSwitch!=null){state.pop();i=indexOf(forcedSwitch);continue;}i=emitSwitch(i,endOffset,state,depth,activeLoops);continue;}
            int before=lines.size();emitLinear(in,state,depth);
            if(ControlFlowGraph.isTerminal(in.opcode())){if(depth==0)terminalReturn=true;return i+1;}
            if(lines.size()==before&&in.opcode()==169){complete=false;add(depth,"// legacy ret bytecode not representable directly in Java source");}
            i++;
        }return i;
    }

    private boolean tryBooleanReturnChain(int start,int end,State state){
        ArrayList<Integer> live=new ArrayList<>();for(int i=start;i<end;i++)if(!deobf.isUnreachable(insns.get(i).offset())&&insns.get(i).opcode()!=0)live.add(i);
        if(live.size()<4)return false;Instruction ret=insns.get(live.get(live.size()-1));if(ret.opcode()!=172)return false;
        int falseConstIdx=live.get(live.size()-2);Instruction falseConst=insns.get(falseConstIdx);if(falseConst.opcode()<3||falseConst.opcode()>4)return false;
        int falseVal=falseConst.opcode()-3;int gotoPos=-1,trueConstIdx=-1;
        for(int k=live.size()-3;k>=0;k--){Instruction x=insns.get(live.get(k));if(ControlFlowGraph.isGoto(x.opcode())&&x.branchTargets().length>0&&x.branchTargets()[0]==ret.offset()){gotoPos=live.get(k);if(k>0)trueConstIdx=live.get(k-1);break;}}
        if(gotoPos<0||trueConstIdx<0)return false;Instruction trueConst=insns.get(trueConstIdx);if(trueConst.opcode()<3||trueConst.opcode()>4)return false;int trueVal=trueConst.opcode()-3;if(trueVal==falseVal)return false;
        State s=state.copy();ArrayList<String> failureConds=new ArrayList<>();int falseOffset=falseConst.offset();
        for(int i=start;i<trueConstIdx;i++){
            Instruction x=insns.get(i);if(deobf.isUnreachable(x.offset())||x.opcode()==0)continue;
            if(ControlFlowGraph.isConditional(x.opcode())){if(x.branchTargets().length==0||x.branchTargets()[0]!=falseOffset)return false;Expr c=branchCondition(x,s);failureConds.add(c.text());continue;}
            if(ControlFlowGraph.isGoto(x.opcode())||x.opcode()==170||x.opcode()==171||ControlFlowGraph.isTerminal(x.opcode()))return false;
            int lineCount=lines.size();emitLinear(x,s,0);if(lines.size()!=lineCount)return false;
        }
        if(failureConds.isEmpty())return false;String fail=String.join(" || ",failureConds.stream().map(this::paren).toList());String expr;if(trueVal==1&&falseVal==0)expr=String.join(" && ",failureConds.stream().map(this::negate).map(this::paren).toList());else expr=fail;
        add(0,"return "+expr+";");terminalReturn=true;return true;
    }

    private boolean tryEmitSimpleExceptionRegion(State state){
        List<CodeAttribute.ExceptionHandler> hs=code.exceptionTable();if(hs.isEmpty())return false;int start=hs.get(0).startPc(),end=hs.get(0).endPc();for(var h:hs)if(h.startPc()!=start||h.endPc()!=end)return false;
        int maxHandler=hs.stream().mapToInt(CodeAttribute.ExceptionHandler::handlerPc).max().orElse(end);int join=findExceptionJoin(start,end,maxHandler);if(join<=end)join=code.code().length;
        emitRange(0,start,state,0,new HashSet<>());add(0,"try {");State tryState=state.copy();int firstHandler=orderedHandlerStart(hs);emitRange(indexOf(start),firstHandler,tryState,1,new HashSet<>());add(0,"}");
        List<CodeAttribute.ExceptionHandler> ordered=new ArrayList<>(hs);ordered.sort(Comparator.comparingInt(CodeAttribute.ExceptionHandler::handlerPc));
        for(int h=0;h<ordered.size();h++){
            var eh=ordered.get(h);String type=eh.catchType()==null?"java.lang.Throwable":SourceDecompiler.javaClass(eh.catchType(),map);String ex="ex"+(h==0?"":h);add(0,"catch ("+type+" "+ex+") {");State cs=state.copy();int idx=indexOf(eh.handlerPc());
            if(idx<insns.size()){Instruction first=insns.get(idx);Integer slot=storeSlot(first);if(slot!=null&&isAStore(first)){cs.aliases.put(slot,Expr.of(ex,type));idx++;}}
            int catchEnd=h+1<ordered.size()?ordered.get(h+1).handlerPc():join;emitRange(idx,catchEnd,cs,1,new HashSet<>());add(0,"}");
        }
        if(join<code.code().length)emitRange(indexOf(join),code.code().length,state,0,new HashSet<>());return true;
    }
    private static int orderedHandlerStart(List<CodeAttribute.ExceptionHandler> hs){return hs.stream().mapToInt(CodeAttribute.ExceptionHandler::handlerPc).min().orElse(0);}
    private int findExceptionJoin(int start,int end,int maxHandler){int best=Integer.MAX_VALUE;for(Instruction i:insns)if(i.offset()>=start&&i.offset()<=end&&ControlFlowGraph.isGoto(i.opcode())&&i.branchTargets().length>0&&i.branchTargets()[0]>maxHandler)best=Math.min(best,i.branchTargets()[0]);return best==Integer.MAX_VALUE?code.code().length:best;}

    private int emitSwitch(int idx,int endOffset,State state,int depth,Set<Integer> activeLoops){
        Instruction sw=insns.get(idx);Expr key=state.pop();SwitchInfo info=parseSwitch(sw);if(info==null){complete=false;add(depth,"// unsupported switch layout at bytecode "+sw.offset());return idx+1;}
        TreeMap<Integer,List<String>> labels=new TreeMap<>();for(int i=0;i<info.keys.length;i++)labels.computeIfAbsent(info.targets[i],x->new ArrayList<>()).add("case "+info.keys[i]+":");labels.computeIfAbsent(info.defaultTarget,x->new ArrayList<>()).add("default:");
        ArrayList<Integer> starts=new ArrayList<>(labels.keySet());Collections.sort(starts);int join=findSwitchJoin(starts,endOffset);add(depth,"switch ("+key.text()+") {");
        for(int s=0;s<starts.size();s++){int off=starts.get(s);for(String label:labels.get(off))add(depth+1,label);int regionEnd=s+1<starts.size()?starts.get(s+1):join;State cs=state.copy();emitRange(indexOf(off),regionEnd,cs,depth+2,activeLoops);Instruction last=lastLiveBefore(regionEnd);if(last==null||(!ControlFlowGraph.isTerminal(last.opcode())&&!ControlFlowGraph.isGoto(last.opcode())))add(depth+2,"break;");}
        add(depth,"}");return join<code.code().length?indexOf(join):indexOf(endOffset);
    }
    private int findSwitchJoin(List<Integer> starts,int endOffset){int best=endOffset;for(int s:starts){for(int i=indexOf(s);i<insns.size()&&insns.get(i).offset()<endOffset;i++){Instruction x=insns.get(i);if(ControlFlowGraph.isGoto(x.opcode())&&x.branchTargets().length>0&&x.branchTargets()[0]>Collections.max(starts))best=Math.min(best,x.branchTargets()[0]);if(i+1<insns.size()&&starts.contains(insns.get(i+1).offset()))break;}}return best;}

    private Expr tryPureTernary(int thenStart,int thenEndOffset,int elseStartOffset,int join,State base,Expr branchCond){
        State a=base.copy(),b=base.copy();int la=lines.size();if(!simulatePure(thenStart,thenEndOffset,a))return null;if(!simulatePure(indexOf(elseStartOffset),join,b))return null;if(lines.size()!=la){while(lines.size()>la)lines.remove(lines.size()-1);return null;}if(a.stack.size()!=base.stack.size()+1||b.stack.size()!=base.stack.size()+1)return null;Expr x=a.pop(),y=b.pop();String t=commonType(x.type(),y.type());return Expr.of("("+negate(branchCond.text())+" ? "+x.text()+" : "+y.text()+")",t);
    }
    private boolean simulatePure(int start,int endOffset,State s){int mark=lines.size();for(int i=start;i<insns.size()&&insns.get(i).offset()<endOffset;i++){Instruction x=insns.get(i);if(deobf.isUnreachable(x.offset())||x.opcode()==0)continue;if(ControlFlowGraph.isConditional(x.opcode())||ControlFlowGraph.isGoto(x.opcode())||x.opcode()==170||x.opcode()==171||ControlFlowGraph.isTerminal(x.opcode())){trim(mark);return false;}int before=lines.size();emitLinear(x,s,0);if(lines.size()!=before){trim(mark);return false;}}trim(mark);return true;}
    private void trim(int mark){while(lines.size()>mark)lines.remove(lines.size()-1);}

    private void emitLinear(Instruction i,State s,int depth){int op=i.opcode();switch(op){
        case 0 -> {}
        case 1 -> s.push(Expr.constant("null","java.lang.Object",null));
        case 2 -> s.push(Expr.constant("-1","int",-1)); case 3,4,5,6,7,8 -> s.push(Expr.constant(Integer.toString(op-3),"int",op-3));
        case 9,10 -> s.push(Expr.constant((op-9)+"L","long",(long)(op-9))); case 11,12,13 -> s.push(Expr.constant((op-11)+".0f","float",(float)(op-11))); case 14,15 -> s.push(Expr.constant((op-14)+".0d","double",(double)(op-14)));
        case 16 -> s.push(Expr.constant(Integer.toString(i.s1(0)),"int",i.s1(0))); case 17 -> s.push(Expr.constant(Short.toString(i.s2(0)),"int",(int)i.s2(0)));
        case 18 -> s.push(exprConstant(cf.constantPool().constant(i.u1(0)))); case 19,20 -> s.push(exprConstant(cf.constantPool().constant(i.u2(0))));
        case 21,22,23,24,25 -> s.push(loadExpr(i.u1(0),i.offset(),s)); case 26,27,28,29 -> s.push(loadExpr(op-26,i.offset(),s));case 30,31,32,33 -> s.push(loadExpr(op-30,i.offset(),s));case 34,35,36,37 -> s.push(loadExpr(op-34,i.offset(),s));case 38,39,40,41 -> s.push(loadExpr(op-38,i.offset(),s));case 42,43,44,45 -> s.push(loadExpr(op-42,i.offset(),s));
        case 46,47,48,49,50,51,52,53 -> {Expr index=s.pop(),array=s.pop();s.push(Expr.of(array.text()+"["+index.text()+"]",arrayComponent(array.type())));}
        case 54,55,56,57,58 -> store(i.u1(0),s.pop(),i,depth,s);case 59,60,61,62 -> store(op-59,s.pop(),i,depth,s);case 63,64,65,66 -> store(op-63,s.pop(),i,depth,s);case 67,68,69,70 -> store(op-67,s.pop(),i,depth,s);case 71,72,73,74 -> store(op-71,s.pop(),i,depth,s);case 75,76,77,78 -> store(op-75,s.pop(),i,depth,s);
        case 79,80,81,82,83,84,85,86 -> {Expr v=s.pop(),ix=s.pop(),arr=s.pop();add(depth,arr.text()+"["+ix.text()+"] = "+v.text()+";");}
        case 87 -> {Expr x=s.pop();if(isSideEffect(x.text()))add(depth,x.text()+";");} case 88 -> pop2(s);
        case 89 -> dup(s,depth); case 90 -> dupX1(s); case 91 -> dupX2(s); case 92 -> dup2(s); case 93 -> dup2X1(s); case 94 -> dup2X2(s); case 95 -> {Expr a=s.pop(),b=s.pop();s.push(a);s.push(b);}
        case 96,97,98,99 -> binary(s,"+");case 100,101,102,103 -> binary(s,"-");case 104,105,106,107 -> binary(s,"*");case 108,109,110,111 -> binary(s,"/");case 112,113,114,115 -> binary(s,"%");
        case 116,117,118,119 -> {Expr x=s.pop();s.push(Expr.of("(-"+x.text()+")",x.type()));}
        case 120,121 -> binary(s,"<<");case 122,123 -> binary(s,">>");case 124,125 -> binary(s,">>>");case 126,127 -> binary(s,"&");case 128,129 -> binary(s,"|");case 130,131 -> binary(s,"^");
        case 132 -> {int slot=i.u1(0),amount=i.s1(1);s.aliases.remove(slot);add(depth,localName(slot,i.offset())+(amount>=0?" += "+amount:" -= "+(-amount))+";");}
        case 133 -> cast(s,"long");case 134 -> cast(s,"float");case 135 -> cast(s,"double");case 136 -> cast(s,"int");case 137 -> cast(s,"float");case 138 -> cast(s,"double");case 139 -> cast(s,"int");case 140 -> cast(s,"long");case 141 -> cast(s,"double");case 142 -> cast(s,"int");case 143 -> cast(s,"long");case 144 -> cast(s,"float");case 145 -> cast(s,"byte");case 146 -> cast(s,"char");case 147 -> cast(s,"short");
        case 148 -> compare(s,"Long.compare");case 149,150 -> compare(s,"Float.compare");case 151,152 -> compare(s,"Double.compare");
        case 169 -> {complete=false;add(depth,"// ret "+i.u1(0)+" (legacy jsr/ret)");}
        case 172,173,174,175,176 -> {add(depth,"return "+s.pop().text()+";");terminalReturn=true;} case 177 -> {if(!"<clinit>".equals(method.name()))add(depth,"return;");terminalReturn=true;}
        case 178 -> getStatic(i.u2(0),s);case 179 -> putStatic(i.u2(0),s,depth);case 180 -> getField(i.u2(0),s);case 181 -> putField(i.u2(0),s,depth);
        case 182,183,184,185 -> invoke(op,i.u2(0),s,depth);case 186 -> invokeDynamic(i.u2(0),s,depth);
        case 187 -> s.push(Expr.fresh(SourceDecompiler.javaClass(cf.constantPool().className(i.u2(0)),map)));case 188 -> {Expr n=s.pop();s.push(Expr.of("new "+primitiveArrayType(i.u1(0))+"["+n.text()+"]",primitiveArrayType(i.u1(0))+"[]"));}case 189 -> {Expr n=s.pop();String t=SourceDecompiler.javaClass(cf.constantPool().className(i.u2(0)),map);s.push(Expr.of("new "+t+"["+n.text()+"]",t+"[]"));}
        case 190 -> {Expr a=s.pop();s.push(Expr.of(a.text()+".length","int"));}case 191 -> {add(depth,"throw "+s.pop().text()+";");terminalReturn=true;}case 192 -> {Expr x=s.pop();String t=SourceDecompiler.javaClass(cf.constantPool().className(i.u2(0)),map);s.push(Expr.of("(("+t+") "+x.text()+")",t));}case 193 -> {Expr x=s.pop();String t=SourceDecompiler.javaClass(cf.constantPool().className(i.u2(0)),map);s.push(Expr.of("("+x.text()+" instanceof "+t+")","boolean"));}
        case 194 -> {Expr x=s.pop();add(depth,"// monitorenter "+x.text());complete=false;}case 195 -> {Expr x=s.pop();add(depth,"// monitorexit "+x.text());complete=false;}case 196 -> emitWide(i,s,depth);case 197 -> multiArray(i,s);
        default -> {complete=false;add(depth,"// unsupported opcode "+i.mnemonic()+" @"+i.offset());}
    }}

    private Expr branchCondition(Instruction i,State s){int op=i.opcode();if(op>=153&&op<=158){Expr a=s.pop();if("boolean".equals(a.type()))return Expr.of(switch(op){case 153->negate(a.text());case 154->paren(a.text());default->paren(a.text())+comparisonZero(op);},"boolean");return Expr.of(paren(a.text())+comparisonZero(op),"boolean");}if(op>=159&&op<=166){Expr b=s.pop(),a=s.pop();String cmp=switch(op){case 159,165->" == ";case 160,166->" != ";case 161->" < ";case 162->" >= ";case 163->" > ";default->" <= ";};return Expr.of("("+a.text()+cmp+b.text()+")","boolean");}if(op==198||op==199){Expr a=s.pop();return Expr.of("("+a.text()+(op==198?" == null":" != null")+")","boolean");}return Expr.of("true /* branch */","boolean");}
    private static String comparisonZero(int op){return switch(op){case 153->" == 0";case 154->" != 0";case 155->" < 0";case 156->" >= 0";case 157->" > 0";default->" <= 0";};}

    private void getStatic(int cp,State s){ConstantPool.MemberRef r=cf.constantPool().memberRef(cp);String t=map.mapJavaType(DescriptorParser.fieldType(r.descriptor()));s.push(Expr.of(staticOwner(r.owner())+"."+fieldName(r),t));}
    private void putStatic(int cp,State s,int depth){ConstantPool.MemberRef r=cf.constantPool().memberRef(cp);add(depth,staticOwner(r.owner())+"."+fieldName(r)+" = "+s.pop().text()+";");}
    private void getField(int cp,State s){ConstantPool.MemberRef r=cf.constantPool().memberRef(cp);Expr o=s.pop();s.push(Expr.of(o.text()+"."+fieldName(r),map.mapJavaType(DescriptorParser.fieldType(r.descriptor()))));}
    private void putField(int cp,State s,int depth){ConstantPool.MemberRef r=cf.constantPool().memberRef(cp);Expr v=s.pop(),o=s.pop();add(depth,o.text()+"."+fieldName(r)+" = "+v.text()+";");}
    private String fieldName(ConstantPool.MemberRef r){return JavaNames.sanitize(map.fieldName(r.owner(),r.name(),r.descriptor()),"field");}

    private void invoke(int op,int cp,State s,int depth){ConstantPool.MemberRef r=cf.constantPool().memberRef(cp);DescriptorParser.MethodDescriptor md=DescriptorParser.method(r.descriptor());ArrayList<Expr> args=new ArrayList<>();for(int n=md.parameterTypes().size()-1;n>=0;n--)args.add(0,s.pop());String joined=String.join(", ",args.stream().map(Expr::text).toList());
        if(op==184){
            if(r.owner().equals(cf.thisClass()) && !"void".equals(md.returnType()) && args.stream().allMatch(e -> e.constant()!=null)) {
                Optional<Object> folded = constantEvaluator.evaluate(cf, r.name(), r.descriptor(), args.stream().map(Expr::constant).toList());
                if(folded.isPresent()) { s.push(exprConstant(folded.get())); return; }
            }
            String call=staticOwner(r.owner())+"."+JavaNames.sanitize(map.methodName(r.owner(),r.name(),r.descriptor()),"method")+"("+joined+")";consumeOrPush(call,map.mapJavaType(md.returnType()),s,depth);return;
        }
        Expr recv=s.pop();if("<init>".equals(r.name())){if("this".equals(recv.text())){add(depth,(Objects.equals(r.owner(),cf.superClass())?"super":"this")+"("+joined+");");return;}if(recv.newType()!=null){String made="new "+recv.newType()+"("+joined+")";if(!s.stack.isEmpty()&&s.peek()==recv){s.pop();s.push(Expr.of(made,recv.newType()));}else if(!s.stack.isEmpty()&&Objects.equals(s.peek().text(),recv.text())){s.pop();s.push(Expr.of(made,recv.newType()));}else add(depth,made+";");return;}add(depth,"// invokespecial <init> on "+recv.text());complete=false;return;}
        String name=JavaNames.sanitize(map.methodName(r.owner(),r.name(),r.descriptor()),"method");String call=recv.text()+"."+name+"("+joined+")";consumeOrPush(call,map.mapJavaType(md.returnType()),s,depth);
    }
    private void invokeDynamic(int cp,State s,int depth){ConstantPool.DynamicRef dr=cf.constantPool().dynamicRef(cp);DescriptorParser.MethodDescriptor md=DescriptorParser.method(dr.descriptor());ArrayList<Expr> args=new ArrayList<>();for(int n=md.parameterTypes().size()-1;n>=0;n--)args.add(0,s.pop());String type=map.mapJavaType(md.returnType());String expr=renderInvokeDynamic(dr,args);if("void".equals(type))add(depth,expr+";");else s.push(Expr.of(expr,type));}
    private String renderInvokeDynamic(ConstantPool.DynamicRef dr,List<Expr> args){BootstrapMethods.BootstrapMethod bm=bootstraps.get(dr.bootstrapMethodIndex());if(bm==null)return "null /* invokedynamic "+dr.name()+" */";try{ConstantPool.MethodHandleEntry mh=(ConstantPool.MethodHandleEntry)cf.constantPool().entry(bm.methodHandleIndex());ConstantPool.MemberRef bsm=cf.constantPool().memberRef(mh.referenceIndex());
        if("java/lang/invoke/StringConcatFactory".equals(bsm.owner()))return concatDynamic(bsm.name(),bm,args);
        if("java/lang/invoke/LambdaMetafactory".equals(bsm.owner()))return lambdaDynamic(bm,args);
        return "null /* invokedynamic "+bsm.owner().replace('/','.')+"."+bsm.name()+" */";
    }catch(RuntimeException ex){return "null /* invokedynamic "+dr.name()+" */";}}
    private String concatDynamic(String name,BootstrapMethods.BootstrapMethod bm,List<Expr> args){if("makeConcatWithConstants".equals(name)&&bm.argumentIndices().length>0){Object recipeObj=cf.constantPool().constant(bm.argumentIndices()[0]);if(recipeObj instanceof String recipe){ArrayList<String> parts=new ArrayList<>();StringBuilder lit=new StringBuilder();int ai=0,ci=1;for(int p=0;p<recipe.length();p++){char c=recipe.charAt(p);if(c=='\u0001'){flushLiteral(parts,lit);parts.add(ai<args.size()?args.get(ai++).text():"null");}else if(c=='\u0002'){flushLiteral(parts,lit);Object v=ci<bm.argumentIndices().length?cf.constantPool().constant(bm.argumentIndices()[ci++]):"";parts.add(exprConstant(v).text());}else lit.append(c);}flushLiteral(parts,lit);return parts.isEmpty()?"\"\"":String.join(" + ",parts);}}return args.isEmpty()?"\"\"":String.join(" + ",args.stream().map(Expr::text).toList());}
    private void flushLiteral(List<String> parts,StringBuilder lit){if(lit.length()>0){parts.add(JavaNames.escapeString(lit.toString()));lit.setLength(0);}}
    private String lambdaDynamic(BootstrapMethods.BootstrapMethod bm,List<Expr> captures){
        if(bm.argumentIndices().length<2)return "null /* lambda */";
        try{
            ConstantPool.MethodHandleEntry impl=(ConstantPool.MethodHandleEntry)cf.constantPool().entry(bm.argumentIndices()[1]);
            ConstantPool.MemberRef r=cf.constantPool().memberRef(impl.referenceIndex());
            String name=JavaNames.sanitize(map.methodName(r.owner(),r.name(),r.descriptor()),"method");
            int kind=impl.referenceKind();
            if(kind==8&&captures.isEmpty())return staticOwner(r.owner())+"::new";
            if(kind==6&&captures.isEmpty())return staticOwner(r.owner())+"::"+name;
            if((kind==5||kind==7||kind==9)&&captures.size()==1)return captures.get(0).text()+"::"+name;
            if((kind==5||kind==9)&&captures.isEmpty())return staticOwner(r.owner())+"::"+name;

            DescriptorParser.MethodDescriptor sam=null;
            Object samType=cf.constantPool().constant(bm.argumentIndices()[0]);
            if(samType instanceof ConstantPool.MethodTypeLiteral mt)sam=DescriptorParser.method(mt.descriptor());
            int paramCount=sam==null?Math.max(1,DescriptorParser.method(r.descriptor()).parameterTypes().size()-captures.size()):sam.parameterTypes().size();
            ArrayList<String> lp=new ArrayList<>();
            for(int i=0;i<paramCount;i++){String t=sam!=null&&i<sam.parameterTypes().size()?map.mapJavaType(sam.parameterTypes().get(i)):"java.lang.Object";lp.add(JavaNames.sanitize(SemanticNameEngine.parameterBase(t,i),"arg"+i));}
            // avoid duplicate parameter names in lambdas such as (value, value)
            HashSet<String> used=new HashSet<>();for(int i=0;i<lp.size();i++){String base=lp.get(i),x=base;int n=2;while(!used.add(x))x=base+n++;lp.set(i,x);}
            ArrayList<String> callArgs=new ArrayList<>();for(Expr e:captures)callArgs.add(e.text());callArgs.addAll(lp);
            String call;
            if(kind==6){call=staticOwner(r.owner())+"."+name+"("+String.join(", ",callArgs)+")";}
            else if(kind==8){call="new "+staticOwner(r.owner())+"("+String.join(", ",callArgs)+")";}
            else {
                String recv;if(!captures.isEmpty()){recv=captures.get(0).text();callArgs.remove(0);}else if(!lp.isEmpty()){recv=lp.get(0);callArgs.remove(captures.size());}else recv="this";
                call=recv+"."+name+"("+String.join(", ",callArgs)+")";
            }
            String params=lp.size()==1?lp.get(0):"("+String.join(", ",lp)+")";
            return params+" -> "+call;
        }catch(RuntimeException ignored){return "null /* lambda */";}
    }
    private void consumeOrPush(String call,String ret,State s,int depth){if("void".equals(ret))add(depth,call+";");else s.push(Expr.of(call,ret));}

    private void store(int slot,Expr v,Instruction in,int depth,State s){s.aliases.remove(slot);if(deobf.deadStoreOffsets().contains(in.offset())){if(isSideEffect(v.text()))add(depth,v.text()+"; // dead local store removed");return;}add(depth,localName(slot,in.offset())+" = "+v.text()+";");}
    private Expr loadExpr(int slot,int pc,State s){Expr a=s.aliases.get(slot);if(a!=null)return a;return Expr.of(localName(slot,pc),localTypes.getOrDefault(slot,"java.lang.Object"));}
    private String localName(int slot,int pc){return localNames.computeIfAbsent(slot,x->{LocalVariableTable.Local lv=lvt.find(slot,pc);return lv==null?"local"+slot:JavaNames.sanitize(lv.name(),"local"+slot);});}

    private void emitWide(Instruction i,State s,int depth){if(i.operands().length<3){complete=false;return;}int op=i.u1(0),slot=i.u2(1);if(op>=21&&op<=25)s.push(loadExpr(slot,i.offset(),s));else if(op>=54&&op<=58)store(slot,s.pop(),i,depth,s);else if(op==132&&i.operands().length>=5){int n=i.s2(3);add(depth,localName(slot,i.offset())+(n>=0?" += "+n:" -= "+(-n))+";");}else{complete=false;add(depth,"// unsupported wide "+OpcodeTable.name(op));}}
    private void multiArray(Instruction i,State s){int dims=i.u1(2);ArrayList<Expr> sizes=new ArrayList<>();for(int n=0;n<dims;n++)sizes.add(0,s.pop());String desc=cf.constantPool().className(i.u2(0));String type=map.mapJavaType(DescriptorParser.fieldType(desc));String base=type;while(base.endsWith("[]"))base=base.substring(0,base.length()-2);StringBuilder x=new StringBuilder("new ").append(base);for(Expr size:sizes)x.append('[').append(size.text()).append(']');int totalDims=0;for(int p=0;p<desc.length()&&desc.charAt(p)=='[';p++)totalDims++;for(int n=sizes.size();n<totalDims;n++)x.append("[]");s.push(Expr.of(x.toString(),type));}

    private void binary(State s,String op){Expr b=s.pop(),a=s.pop();String type=numericType(a.type(),b.type());Object c=fold(a.constant(),b.constant(),op);String text="("+a.text()+" "+op+" "+b.text()+")";s.push(c==null?Expr.of(text,type):Expr.constant(formatNumber(c),type,c));}
    private void cast(State s,String t){Expr x=s.pop();s.push(Expr.of("(("+t+") "+x.text()+")",t));}private void compare(State s,String fn){Expr b=s.pop(),a=s.pop();s.push(Expr.of(fn+"("+a.text()+", "+b.text()+")","int"));}
    private static Object fold(Object a,Object b,String op){if(!(a instanceof Number x)||!(b instanceof Number y))return null;try{if(a instanceof Double||b instanceof Double){double p=x.doubleValue(),q=y.doubleValue();return switch(op){case "+"->p+q;case "-"->p-q;case "*"->p*q;case "/"->p/q;case "%"->p%q;default->null;};}if(a instanceof Float||b instanceof Float){float p=x.floatValue(),q=y.floatValue();return switch(op){case "+"->p+q;case "-"->p-q;case "*"->p*q;case "/"->p/q;case "%"->p%q;default->null;};}long p=x.longValue(),q=y.longValue();long r=switch(op){case "+"->p+q;case "-"->p-q;case "*"->p*q;case "/"->p/q;case "%"->p%q;case "&"->p&q;case "|"->p|q;case "^"->p^q;case "<<"->p<<(q&63);case ">>"->p>>(q&63);case ">>>"->p>>>(q&63);default->Long.MIN_VALUE;};if(r==Long.MIN_VALUE)return null;return a instanceof Integer&&b instanceof Integer?(int)r:r;}catch(RuntimeException ex){return null;}}
    private static String formatNumber(Object c){if(c instanceof Long x)return x+"L";if(c instanceof Float x)return x+"f";if(c instanceof Double x)return x+"d";return String.valueOf(c);}private static String numericType(String a,String b){if("double".equals(a)||"double".equals(b))return "double";if("float".equals(a)||"float".equals(b))return "float";if("long".equals(a)||"long".equals(b))return "long";return "int";}

    private Expr exprConstant(Object v){if(v instanceof String x)return Expr.constant(JavaNames.escapeString(x),"java.lang.String",x);if(v instanceof Integer x)return Expr.constant(x.toString(),"int",x);if(v instanceof Long x)return Expr.constant(x+"L","long",x);if(v instanceof Float x)return Expr.constant(x+"f","float",x);if(v instanceof Double x)return Expr.constant(x+"d","double",x);if(v instanceof Character x)return Expr.constant("'"+escapeChar(x)+"'","char",x);if(v instanceof Boolean x)return Expr.constant(x.toString(),"boolean",x);if(v instanceof ConstantPool.ClassLiteral c)return Expr.of(SourceDecompiler.javaClass(c.internalName(),map)+".class","java.lang.Class");if(v instanceof ConstantPool.MethodTypeLiteral)return Expr.of("null /* method type */","java.lang.invoke.MethodType");if(v instanceof ConstantPool.DynamicRef d)return Expr.of("null /* constant-dynamic "+d.name()+" */",map.mapJavaType(DescriptorParser.fieldType(d.descriptor())));return Expr.of("null /* cp */","java.lang.Object");}
    private static String typeOfConstant(Object v){if(v instanceof String)return "java.lang.String";if(v instanceof Integer)return "int";if(v instanceof Long)return "long";if(v instanceof Float)return "float";if(v instanceof Double)return "double";if(v instanceof ConstantPool.ClassLiteral)return "java.lang.Class";return "java.lang.Object";}

    private int findLoopBackedge(int from,int exitOffset,int header){int end=indexOf(exitOffset);for(int i=end-1;i>=from;i--){Instruction x=insns.get(i);if(deobf.isUnreachable(x.offset())||x.opcode()==0)continue;if(ControlFlowGraph.isGoto(x.opcode())&&threadedTarget(x)<=header)return i;break;}return -1;}
    private int previousLiveIndex(int offset){int idx=indexOf(offset)-1;while(idx>=0&&(deobf.isUnreachable(insns.get(idx).offset())||insns.get(idx).opcode()==0))idx--;return idx;}
    private Instruction lastLiveBefore(int offset){int i=previousLiveIndex(offset);return i>=0?insns.get(i):null;}
    private int threadedTarget(Instruction i){return deobf.threadedTarget(i.offset(),i.branchTargets().length==0?i.offset()+i.length():i.branchTargets()[0]);}
    private int indexOf(int offset){Integer i=indexByOffset.get(offset);if(i!=null)return i;int p=Collections.binarySearch(insns,new Instruction(offset,0,"",new byte[0],0,new int[0]),Comparator.comparingInt(Instruction::offset));return p>=0?p:Math.min(insns.size(),-p-1);}

    private static void mergeStacks(State dst,State a,State b){dst.stack.clear();if(a.stack.size()!=b.stack.size())return;for(int i=0;i<a.stack.size();i++){Expr x=a.stack.get(i),y=b.stack.get(i);dst.stack.add(Objects.equals(x.text(),y.text())?x:Expr.of("/* phi */ ("+x.text()+")",commonType(x.type(),y.type())));}}
    private static void mergeStacksConservative(State dst,State branch){if(dst.stack.size()!=branch.stack.size())return;for(int i=0;i<dst.stack.size();i++)if(!Objects.equals(dst.stack.get(i).text(),branch.stack.get(i).text()))dst.stack.set(i,Expr.of("/* phi */ "+dst.stack.get(i).text(),commonType(dst.stack.get(i).type(),branch.stack.get(i).type())));}
    private static String commonType(String a,String b){return Objects.equals(a,b)?a:"java.lang.Object";}

    private void dup(State s,int depth){
        Expr top=s.peek();
        if(top.type()!=null&&top.type().endsWith("[]")&&top.text().startsWith("new ")){
            s.pop();String n="aegisArray"+(syntheticArrayId++);add(depth,top.type()+" "+n+" = "+top.text()+";");Expr ref=Expr.of(n,top.type());s.push(ref);s.push(ref);return;
        }
        s.push(top);
    }
    private void pop2(State s){Expr a=s.pop();if(a.width()==1)s.pop();}private void dupX1(State s){Expr a=s.pop(),b=s.pop();s.push(a);s.push(b);s.push(a);}private void dupX2(State s){Expr a=s.pop(),b=s.pop();if(b.width()==2){s.push(a);s.push(b);s.push(a);}else{Expr c=s.pop();s.push(a);s.push(c);s.push(b);s.push(a);}}private void dup2(State s){Expr a=s.pop();if(a.width()==2){s.push(a);s.push(a);}else{Expr b=s.pop();s.push(b);s.push(a);s.push(b);s.push(a);}}private void dup2X1(State s){Expr a=s.pop();if(a.width()==2){Expr b=s.pop();s.push(a);s.push(b);s.push(a);}else{Expr b=s.pop(),c=s.pop();s.push(b);s.push(a);s.push(c);s.push(b);s.push(a);}}private void dup2X2(State s){Expr a=s.pop(),b=s.pop();if(a.width()==2&&b.width()==2){s.push(a);s.push(b);s.push(a);}else{complete=false;s.push(b);s.push(a);s.push(b);s.push(a);}}

    private static String escapeChar(char c){return switch(c){case '\\'->"\\\\";case '\''->"\\'";case '\n'->"\\n";case '\r'->"\\r";case '\t'->"\\t";default->Character.isISOControl(c)?String.format("\\u%04x",(int)c):Character.toString(c);};}
    private static String primitiveArrayType(int a){return switch(a){case 4->"boolean";case 5->"char";case 6->"float";case 7->"double";case 8->"byte";case 9->"short";case 10->"int";case 11->"long";default->"java.lang.Object";};}private static String arrayComponent(String t){return t!=null&&t.endsWith("[]")?t.substring(0,t.length()-2):"java.lang.Object";}
    private String staticOwner(String internal){String j=SourceDecompiler.javaClass(internal,map);if(internal.equals(cf.thisClass())){int dot=j.lastIndexOf('.');return dot<0?j:j.substring(dot+1);}return j;}
    private static boolean isSideEffect(String x){return x.contains("(")||x.contains("=")||x.startsWith("new ");}
    private void add(int depth,String text){lines.add("    ".repeat(Math.max(0,depth))+text);}private String paren(String s){return s.startsWith("(")&&s.endsWith(")")?s:"("+s+")";}private String negate(String s){String x=s.trim();if(x.startsWith("!")&&!x.startsWith("!=("))return x.substring(1);if(x.contains(" == "))return x.replace(" == "," != ");if(x.contains(" != "))return x.replace(" != "," == ");if(x.contains(" >= "))return x.replace(" >= "," < ");if(x.contains(" <= "))return x.replace(" <= "," > ");if(x.contains(" > "))return x.replace(" > "," <= ");if(x.contains(" < "))return x.replace(" < "," >= ");return "!("+x+")";}
    private static String safe(String s){return s==null?"unknown":s.replace('\n',' ').replace('\r',' ');}
    private static boolean isAStore(Instruction i){int op=i.opcode();return op==58||(op>=75&&op<=78)||(op==196&&i.operands().length>0&&i.u1(0)==58);}private static Integer storeSlot(Instruction i){int op=i.opcode();if(op>=54&&op<=58)return i.u1(0);if(op>=59&&op<=62)return op-59;if(op>=63&&op<=66)return op-63;if(op>=67&&op<=70)return op-67;if(op>=71&&op<=74)return op-71;if(op>=75&&op<=78)return op-75;if(op==196&&i.operands().length>=3&&i.u1(0)>=54&&i.u1(0)<=58)return i.u2(1);return null;}

    private static final class SwitchInfo {final int defaultTarget;final int[] keys,targets;SwitchInfo(int d,int[]k,int[]t){defaultTarget=d;keys=k;targets=t;}}
    private SwitchInfo parseSwitch(Instruction in){byte[] b=in.operands();int pad=(4-((in.offset()+1)&3))&3;int p=pad;try{int def=in.offset()+s4(b,p);p+=4;if(in.opcode()==170){int low=s4(b,p);p+=4;int high=s4(b,p);p+=4;int n=high-low+1;int[]k=new int[n],t=new int[n];for(int i=0;i<n;i++){k[i]=low+i;t[i]=in.offset()+s4(b,p);p+=4;}return new SwitchInfo(def,k,t);}int n=s4(b,p);p+=4;int[]k=new int[n],t=new int[n];for(int i=0;i<n;i++){k[i]=s4(b,p);p+=4;t[i]=in.offset()+s4(b,p);p+=4;}return new SwitchInfo(def,k,t);}catch(RuntimeException ex){return null;}}
    private static int s4(byte[]b,int p){return ((b[p]&255)<<24)|((b[p+1]&255)<<16)|((b[p+2]&255)<<8)|(b[p+3]&255);}
}
