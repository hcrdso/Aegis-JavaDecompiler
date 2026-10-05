package dev.aegis.deobfuscate;

import dev.aegis.cfg.ControlFlowGraph;
import dev.aegis.classfile.*;
import java.util.*;

public final class Deobfuscator {
    private static final Object UNKNOWN = new Object();
    private static final Object NULL = new Object();
    private static final Object NON_NULL = new Object();

    public DeobfuscationResult analyze(ClassFile cf, MemberInfo method, CodeAttribute code) {
        List<Instruction> insns = BytecodeDecoder.decode(code.code());
        ControlFlowGraph cfg = ControlFlowGraph.build(insns, code);
        BranchFacts facts = constantBranchAnalysis(cf, method, code, cfg);
        Map<Integer, Boolean> forced = facts.conditionals();
        Map<Integer, Integer> forcedSwitches = facts.switchTargets();
        Set<Integer> unreachable = unreachableWithForced(cfg, forced, forcedSwitches);
        Map<Integer, Integer> threaded = threadJumps(insns, unreachable);
        Set<Integer> deadStores = findDeadStores(cfg, unreachable);

        int nops = 0, folds = 0, redundant = 0;
        for (int i = 0; i < insns.size(); i++) {
            Instruction in = insns.get(i);
            if (unreachable.contains(in.offset())) continue;
            if (in.opcode() == 0) nops++;
            if (isArithmetic(in.opcode())) folds += locallyFoldable(insns, i) ? 1 : 0;
            if (ControlFlowGraph.isGoto(in.opcode()) && in.branchTargets().length > 0) {
                int next = i + 1 < insns.size() ? insns.get(i + 1).offset() : code.code().length;
                if (in.branchTargets()[0] == next || threaded.getOrDefault(in.offset(), in.branchTargets()[0]) == next) redundant++;
            }
        }
        ArrayList<String> notes = new ArrayList<>();
        if (!forced.isEmpty()) notes.add("Resolved " + forced.size() + " constant/opaque conditional branch(es)");
        if (!forcedSwitches.isEmpty()) notes.add("Resolved " + forcedSwitches.size() + " constant switch/dispatcher(s)");
        if (!unreachable.isEmpty()) notes.add("Removed " + unreachable.size() + " unreachable instruction(s) from source reconstruction");
        if (!threaded.isEmpty()) notes.add("Threaded " + threaded.size() + " goto chain(s)");
        if (!deadStores.isEmpty()) notes.add("Detected " + deadStores.size() + " dead local store(s)");
        if (!cfg.naturalLoops().isEmpty()) notes.add("Detected " + cfg.naturalLoops().size() + " natural loop(s) using dominators");
        DeobfuscationReport report = new DeobfuscationReport(unreachable.size(), forced.size() + forcedSwitches.size(), redundant,
                folds, deadStores.size(), nops, List.copyOf(notes));
        return new DeobfuscationResult(insns, Set.copyOf(unreachable), Map.copyOf(forced), Map.copyOf(forcedSwitches), Map.copyOf(threaded),
                Set.copyOf(deadStores), report);
    }

    private record BranchFacts(Map<Integer,Boolean> conditionals, Map<Integer,Integer> switchTargets) {}

    private BranchFacts constantBranchAnalysis(ClassFile cf, MemberInfo method, CodeAttribute code, ControlFlowGraph cfg) {
        if (cfg.entry() == null) return new BranchFacts(Map.of(), Map.of());
        Map<ControlFlowGraph.BasicBlock, Frame> entries = new HashMap<>();
        ArrayDeque<ControlFlowGraph.BasicBlock> work = new ArrayDeque<>();
        entries.put(cfg.entry(), initialFrame(method, code.maxLocals())); work.add(cfg.entry());
        LinkedHashMap<Integer, Boolean> forced = new LinkedHashMap<>();
        LinkedHashMap<Integer, Integer> forcedSwitches = new LinkedHashMap<>();
        ConstantMethodEvaluator evaluator = new ConstantMethodEvaluator();
        int budget = Math.max(4000, cfg.blocks().size() * 400);
        while (!work.isEmpty() && budget-- > 0) {
            ControlFlowGraph.BasicBlock b = work.removeFirst();
            Frame f = entries.get(b).copy();
            Instruction last = null;
            for (Instruction in : b.instructions()) {
                last = in;
                if (ControlFlowGraph.isConditional(in.opcode())) {
                    Boolean taken = evaluateConditional(in.opcode(), f);
                    if (taken != null) forced.put(in.offset(), taken);
                    consumeConditional(in.opcode(), f);
                } else if (in.opcode() == 170 || in.opcode() == 171) {
                    Object key = f.pop();
                    Integer target = evaluateSwitchTarget(in, key);
                    if (target != null) forcedSwitches.put(in.offset(), target);
                } else transfer(cf, in, f, evaluator);
            }
            if (last == null) continue;
            if (ControlFlowGraph.isConditional(last.opcode()) && last.branchTargets().length > 0) {
                Boolean taken = forced.get(last.offset());
                if (taken != null) {
                    int wanted = taken ? last.branchTargets()[0] : fallthroughOffset(b, cfg);
                    ControlFlowGraph.BasicBlock target = cfg.blockAtStart(wanted);
                    if (target != null) mergeAndQueue(entries, target, f, work);
                } else for (ControlFlowGraph.BasicBlock s : b.successors()) mergeAndQueue(entries, s, f, work);
            } else if (last.opcode() == 170 || last.opcode() == 171) {
                Integer wanted = forcedSwitches.get(last.offset());
                if (wanted != null) {
                    ControlFlowGraph.BasicBlock target = cfg.blockAtStart(wanted);
                    if (target != null) mergeAndQueue(entries,target,f,work);
                } else for (ControlFlowGraph.BasicBlock x : b.successors()) mergeAndQueue(entries,x,f,work);
            } else {
                for (ControlFlowGraph.BasicBlock x : b.successors()) mergeAndQueue(entries, x, f, work);
            }
            for (ControlFlowGraph.BasicBlock h : b.exceptionalSuccessors()) {
                Frame ex = f.copy(); ex.stack.clear(); ex.stack.add(UNKNOWN);
                mergeAndQueue(entries, h, ex, work);
            }
        }
        return new BranchFacts(Map.copyOf(forced), Map.copyOf(forcedSwitches));
    }

    private static int fallthroughOffset(ControlFlowGraph.BasicBlock b, ControlFlowGraph cfg) {
        Instruction last = b.last();
        int next = last.offset() + last.length();
        return cfg.blockAtStart(next) == null ? next : next;
    }

    private static void mergeAndQueue(Map<ControlFlowGraph.BasicBlock, Frame> entries,
                                      ControlFlowGraph.BasicBlock target, Frame incoming,
                                      ArrayDeque<ControlFlowGraph.BasicBlock> work) {
        Frame old = entries.get(target);
        if (old == null) { entries.put(target, incoming.copy()); work.add(target); return; }
        Frame merged = Frame.merge(old, incoming);
        if (!merged.equals(old)) { entries.put(target, merged); work.add(target); }
    }

    private static Frame initialFrame(MemberInfo method, int maxLocals) {
        Frame f = new Frame(maxLocals);
        DescriptorParser.MethodDescriptor md;
        try { md = DescriptorParser.method(method.descriptor()); } catch (RuntimeException ex) { return f; }
        int slot = 0;
        if (!AccessFlags.has(method.accessFlags(), AccessFlags.STATIC)) f.locals[slot++] = UNKNOWN;
        for (int i = 0; i < md.parameterTypes().size(); i++) {
            f.locals[slot] = UNKNOWN; slot += md.slotWidths().get(i);
        }
        return f;
    }

    private static void transfer(ClassFile cf, Instruction in, Frame f, ConstantMethodEvaluator evaluator) {
        int op = in.opcode();
        try {
            switch (op) {
                case 0 -> { }
                case 1 -> f.push(NULL);
                case 2 -> f.push(-1);
                case 3,4,5,6,7,8 -> f.push(op - 3);
                case 9,10 -> f.push((long)(op - 9));
                case 11,12,13 -> f.push((float)(op - 11));
                case 14,15 -> f.push((double)(op - 14));
                case 16 -> f.push(in.s1(0));
                case 17 -> f.push((int)in.s2(0));
                case 18 -> f.push(constant(cf, in.u1(0)));
                case 19,20 -> f.push(constant(cf, in.u2(0)));
                case 21,22,23,24,25 -> f.push(f.local(in.u1(0)));
                case 26,27,28,29 -> f.push(f.local(op - 26));
                case 30,31,32,33 -> f.push(f.local(op - 30));
                case 34,35,36,37 -> f.push(f.local(op - 34));
                case 38,39,40,41 -> f.push(f.local(op - 38));
                case 42,43,44,45 -> f.push(f.local(op - 42));
                case 46,47,48,49,50,51,52,53 -> { f.pop(); f.pop(); f.push(UNKNOWN); }
                case 54,55,56,57,58 -> f.setLocal(in.u1(0), f.pop());
                case 59,60,61,62 -> f.setLocal(op - 59, f.pop());
                case 63,64,65,66 -> f.setLocal(op - 63, f.pop());
                case 67,68,69,70 -> f.setLocal(op - 67, f.pop());
                case 71,72,73,74 -> f.setLocal(op - 71, f.pop());
                case 75,76,77,78 -> f.setLocal(op - 75, f.pop());
                case 79,80,81,82,83,84,85,86 -> { f.pop(); f.pop(); f.pop(); }
                case 87 -> f.pop();
                case 88 -> { f.pop(); f.pop(); }
                case 89 -> f.push(f.peek());
                case 90 -> { Object a=f.pop(), b=f.pop(); f.push(a); f.push(b); f.push(a); }
                case 91,92,93,94 -> f.clearStack();
                case 95 -> { Object a=f.pop(), b=f.pop(); f.push(a); f.push(b); }
                case 96,97,98,99 -> arithmetic(f, '+');
                case 100,101,102,103 -> arithmetic(f, '-');
                case 104,105,106,107 -> arithmetic(f, '*');
                case 108,109,110,111 -> arithmetic(f, '/');
                case 112,113,114,115 -> arithmetic(f, '%');
                case 116,117,118,119 -> unaryNeg(f);
                case 120,121 -> arithmetic(f, '<');
                case 122,123 -> arithmetic(f, '>');
                case 124,125 -> arithmetic(f, 'u');
                case 126,127 -> arithmetic(f, '&');
                case 128,129 -> arithmetic(f, '|');
                case 130,131 -> arithmetic(f, '^');
                case 132 -> {
                    int idx=in.u1(0), amount=in.s1(1); Object v=f.local(idx);
                    if (v instanceof Integer x) f.setLocal(idx, x + amount); else f.setLocal(idx, UNKNOWN);
                }
                case 133,134,135,136,137,138,139,140,141,142,143,144,145,146,147 -> convert(op, f);
                case 148,149,150,151,152 -> compare(op, f);
                case 167,168,169,170,171,172,173,174,175,176,177,191,198,199,200,201 -> { /* handled by CFG/condition */ }
                case 178 -> f.push(staticFieldConstant(cf, in));
                case 179 -> f.pop();
                case 180 -> { f.pop(); f.push(UNKNOWN); }
                case 181 -> { f.pop(); f.pop(); }
                case 182,183,184,185 -> invoke(cf, in, f, op, evaluator);
                case 186 -> invokeDynamic(cf, in, f);
                case 187 -> f.push(NON_NULL);
                case 188,189 -> { f.pop(); f.push(NON_NULL); }
                case 190 -> { f.pop(); f.push(UNKNOWN); }
                case 192 -> { Object x=f.pop(); f.push(x); }
                case 193 -> { Object x=f.pop(); f.push(x == NULL ? 0 : UNKNOWN); }
                case 194,195 -> f.pop();
                case 196 -> transferWide(in, f);
                case 197 -> { int dims=in.u1(2); for(int i=0;i<dims;i++) f.pop(); f.push(NON_NULL); }
                default -> f.clearStack();
            }
        } catch (RuntimeException ex) {
            f.clearStack();
        }
    }

    private static void transferWide(Instruction in, Frame f) {
        if (in.operands().length < 3) { f.clearStack(); return; }
        int widened=in.u1(0), idx=in.u2(1);
        if (widened >= 21 && widened <= 25) f.push(f.local(idx));
        else if (widened >= 54 && widened <= 58) f.setLocal(idx, f.pop());
        else if (widened == 132 && in.operands().length >= 5) {
            int amount=in.s2(3); Object v=f.local(idx); f.setLocal(idx, v instanceof Integer x ? x + amount : UNKNOWN);
        }
    }

    private static Object constant(ClassFile cf, int index) {
        Object x=cf.constantPool().constant(index);
        return (x instanceof Number || x instanceof String) ? x : UNKNOWN;
    }

    private static void invoke(ClassFile cf, Instruction in, Frame f, int op, ConstantMethodEvaluator evaluator) {
        ConstantPool.MemberRef r=cf.constantPool().memberRef(in.u2(0));
        DescriptorParser.MethodDescriptor md=DescriptorParser.method(r.descriptor());
        ArrayList<Object> args=new ArrayList<>();
        for(int i=md.parameterTypes().size()-1;i>=0;i--) args.add(0,f.pop());
        Object receiver=op==184?null:f.pop();
        if(md.returnType().equals("void")) return;

        Object result=UNKNOWN;
        boolean constants=true; for(Object a:args) if(a==UNKNOWN){constants=false;break;}
        if(op==184 && r.owner().equals(cf.thisClass()) && constants) {
            try { Optional<Object> v=evaluator.evaluate(cf,r.name(),r.descriptor(),args); if(v.isPresent()) result=v.get(); } catch(RuntimeException ignored) { }
        }
        if(result==UNKNOWN) result=knownLibraryCall(r,receiver,args);
        f.push(result);
    }

    private static Object knownLibraryCall(ConstantPool.MemberRef r,Object receiver,List<Object> args){
        try{
            if("java/lang/String".equals(r.owner()) && receiver instanceof String s){
                return switch(r.name()){
                    case "length" -> s.length();
                    case "isEmpty" -> s.isEmpty()?1:0;
                    case "charAt" -> (int)s.charAt(((Number)args.get(0)).intValue());
                    case "equals" -> Objects.equals(s,args.get(0))?1:0;
                    case "equalsIgnoreCase" -> s.equalsIgnoreCase((String)args.get(0))?1:0;
                    case "startsWith" -> s.startsWith((String)args.get(0))?1:0;
                    case "endsWith" -> s.endsWith((String)args.get(0))?1:0;
                    case "contains" -> s.contains((CharSequence)args.get(0))?1:0;
                    case "concat" -> s.concat((String)args.get(0));
                    case "substring" -> args.size()==1?s.substring(((Number)args.get(0)).intValue()):s.substring(((Number)args.get(0)).intValue(),((Number)args.get(1)).intValue());
                    case "indexOf" -> args.get(0) instanceof String x?s.indexOf(x):s.indexOf(((Number)args.get(0)).intValue());
                    case "lastIndexOf" -> args.get(0) instanceof String x?s.lastIndexOf(x):s.lastIndexOf(((Number)args.get(0)).intValue());
                    default -> UNKNOWN;
                };
            }
            if("java/lang/String".equals(r.owner()) && r.name().equals("valueOf") && args.size()==1 && args.get(0)!=UNKNOWN && args.get(0)!=NULL && args.get(0)!=NON_NULL)
                return String.valueOf(args.get(0));
            if("java/util/Objects".equals(r.owner())){
                if(r.name().equals("equals")&&args.size()==2&&args.get(0)!=UNKNOWN&&args.get(1)!=UNKNOWN&&args.get(0)!=NON_NULL&&args.get(1)!=NON_NULL)return Objects.equals(args.get(0),args.get(1))?1:0;
                if(r.name().equals("isNull")&&args.size()==1&&args.get(0)!=UNKNOWN)return args.get(0)==NULL?1:0;
                if(r.name().equals("nonNull")&&args.size()==1&&args.get(0)!=UNKNOWN)return args.get(0)==NULL?0:1;
            }
            if("java/lang/Integer".equals(r.owner())){
                if(r.name().equals("compare")&&args.size()==2)return Integer.compare(((Number)args.get(0)).intValue(),((Number)args.get(1)).intValue());
                if(r.name().equals("parseInt")&&args.size()>=1&&args.get(0) instanceof String x)return args.size()==1?Integer.parseInt(x):Integer.parseInt(x,((Number)args.get(1)).intValue());
                if(r.name().equals("rotateLeft")&&args.size()==2)return Integer.rotateLeft(((Number)args.get(0)).intValue(),((Number)args.get(1)).intValue());
                if(r.name().equals("rotateRight")&&args.size()==2)return Integer.rotateRight(((Number)args.get(0)).intValue(),((Number)args.get(1)).intValue());
                if(r.name().equals("reverse")&&args.size()==1)return Integer.reverse(((Number)args.get(0)).intValue());
                if(r.name().equals("bitCount")&&args.size()==1)return Integer.bitCount(((Number)args.get(0)).intValue());
            }
            if("java/lang/Long".equals(r.owner())){
                if(r.name().equals("compare")&&args.size()==2)return Long.compare(((Number)args.get(0)).longValue(),((Number)args.get(1)).longValue());
                if(r.name().equals("parseLong")&&args.size()>=1&&args.get(0) instanceof String x)return args.size()==1?Long.parseLong(x):Long.parseLong(x,((Number)args.get(1)).intValue());
                if(r.name().equals("rotateLeft")&&args.size()==2)return Long.rotateLeft(((Number)args.get(0)).longValue(),((Number)args.get(1)).intValue());
                if(r.name().equals("rotateRight")&&args.size()==2)return Long.rotateRight(((Number)args.get(0)).longValue(),((Number)args.get(1)).intValue());
                if(r.name().equals("reverse")&&args.size()==1)return Long.reverse(((Number)args.get(0)).longValue());
                if(r.name().equals("bitCount")&&args.size()==1)return Long.bitCount(((Number)args.get(0)).longValue());
            }
            if("java/lang/Math".equals(r.owner())&&args.stream().allMatch(Number.class::isInstance)){
                Number a=(Number)args.get(0); Number b=args.size()>1?(Number)args.get(1):null;
                boolean wide=r.descriptor().endsWith("J")||r.descriptor().contains("J");
                return switch(r.name()){
                    case "abs" -> wide?Math.abs(a.longValue()):Math.abs(a.intValue());
                    case "min" -> wide?Math.min(a.longValue(),b.longValue()):Math.min(a.intValue(),b.intValue());
                    case "max" -> wide?Math.max(a.longValue(),b.longValue()):Math.max(a.intValue(),b.intValue());
                    default -> UNKNOWN;
                };
            }
        }catch(RuntimeException ignored){}
        return UNKNOWN;
    }

    private static Object staticFieldConstant(ClassFile cf,Instruction in){
        try{
            ConstantPool.MemberRef r=cf.constantPool().memberRef(in.u2(0));
            if(!r.owner().equals(cf.thisClass()))return UNKNOWN;
            for(MemberInfo f:cf.fields())if(f.name().equals(r.name())&&f.descriptor().equals(r.descriptor())
                    &&AccessFlags.has(f.accessFlags(),AccessFlags.STATIC)&&AccessFlags.has(f.accessFlags(),AccessFlags.FINAL)){
                AttributeInfo cv=f.attribute("ConstantValue");if(cv==null||cv.data().length!=2)return UNKNOWN;
                Object x=cf.constantPool().constant(cv.reader().u2());
                return (x instanceof Number||x instanceof String)?x:UNKNOWN;
            }
        }catch(RuntimeException ignored){}
        return UNKNOWN;
    }
    private static void invokeDynamic(ClassFile cf, Instruction in, Frame f) {
        ConstantPool.DynamicRef r=cf.constantPool().dynamicRef(in.u2(0));
        DescriptorParser.MethodDescriptor md=DescriptorParser.method(r.descriptor());
        for(int i=md.parameterTypes().size()-1;i>=0;i--) f.pop();
        if(!md.returnType().equals("void")) f.push(UNKNOWN);
    }

    private static Boolean evaluateConditional(int op, Frame f) {
        try {
            if (op >= 153 && op <= 158) {
                Object a=f.peek(); if (!(a instanceof Number n)) return null; long x=n.longValue();
                return switch(op){case 153->x==0; case 154->x!=0; case 155->x<0; case 156->x>=0; case 157->x>0; default->x<=0;};
            }
            if (op >= 159 && op <= 164) {
                Object b=f.peek(0), a=f.peek(1); if (!(a instanceof Number x) || !(b instanceof Number y)) return null;
                long av=x.longValue(), bv=y.longValue();
                return switch(op){case 159->av==bv; case 160->av!=bv; case 161->av<bv; case 162->av>=bv; case 163->av>bv; default->av<=bv;};
            }
            if (op == 165 || op == 166) {
                Object b=f.peek(0), a=f.peek(1); if (a==UNKNOWN || b==UNKNOWN) return null;
                boolean eq = a == b || Objects.equals(a,b); return op==165 ? eq : !eq;
            }
            if (op == 198 || op == 199) {
                Object a=f.peek(); if (a==UNKNOWN) return null; boolean isNull=a==NULL; return op==198 ? isNull : !isNull;
            }
        } catch (RuntimeException ignored) { }
        return null;
    }

    private static void consumeConditional(int op, Frame f) {
        if (op >= 159 && op <= 166) { f.pop(); f.pop(); }
        else f.pop();
    }

    private static Integer evaluateSwitchTarget(Instruction in,Object key){
        if(!(key instanceof Number n))return null;
        int value=n.intValue();byte[] b=in.operands();int pad=(4-((in.offset()+1)&3))&3;int p=pad;
        try{
            int def=in.offset()+s4(b,p);p+=4;
            if(in.opcode()==170){int low=s4(b,p);p+=4;int high=s4(b,p);p+=4;if(value<low||value>high)return def;p+=(value-low)*4;return in.offset()+s4(b,p);}
            int count=s4(b,p);p+=4;for(int i=0;i<count;i++){int k=s4(b,p);p+=4;int target=in.offset()+s4(b,p);p+=4;if(k==value)return target;}return def;
        }catch(RuntimeException ex){return null;}
    }
    private static int s4(byte[] b,int p){return((b[p]&255)<<24)|((b[p+1]&255)<<16)|((b[p+2]&255)<<8)|(b[p+3]&255);}

    private static Set<Integer> unreachableWithForced(ControlFlowGraph cfg, Map<Integer, Boolean> forced, Map<Integer,Integer> forcedSwitches) {
        if (cfg.entry()==null) return Set.of();
        Set<ControlFlowGraph.BasicBlock> seen=new LinkedHashSet<>(); ArrayDeque<ControlFlowGraph.BasicBlock> q=new ArrayDeque<>(); q.add(cfg.entry());
        while(!q.isEmpty()) {
            ControlFlowGraph.BasicBlock b=q.removeFirst(); if(!seen.add(b)) continue;
            Instruction last=b.last(); Boolean take=last==null?null:forced.get(last.offset());
            if(last!=null && take!=null && ControlFlowGraph.isConditional(last.opcode())) {
                int target = take ? last.branchTargets()[0] : last.offset()+last.length();
                ControlFlowGraph.BasicBlock t=cfg.blockAtStart(target); if(t!=null) q.add(t);
            } else if(last!=null && (last.opcode()==170||last.opcode()==171) && forcedSwitches.containsKey(last.offset())) {
                ControlFlowGraph.BasicBlock t=cfg.blockAtStart(forcedSwitches.get(last.offset())); if(t!=null) q.add(t);
            } else q.addAll(b.successors());
            q.addAll(b.exceptionalSuccessors());
        }
        LinkedHashSet<Integer> dead=new LinkedHashSet<>();
        for(ControlFlowGraph.BasicBlock b:cfg.blocks()) if(!seen.contains(b)) for(Instruction i:b.instructions()) dead.add(i.offset());
        return dead;
    }

    private static Map<Integer,Integer> threadJumps(List<Instruction> insns, Set<Integer> dead) {
        Map<Integer,Instruction> byOffset=new HashMap<>(); for(Instruction i:insns) byOffset.put(i.offset(),i);
        LinkedHashMap<Integer,Integer> result=new LinkedHashMap<>();
        for(Instruction in:insns) {
            if(dead.contains(in.offset()) || !ControlFlowGraph.isGoto(in.opcode()) || in.branchTargets().length==0) continue;
            int target=in.branchTargets()[0], original=target; HashSet<Integer> seen=new HashSet<>();
            while(seen.add(target)) {
                Instruction t=byOffset.get(target); if(t==null) break;
                if(t.opcode()==0) { target += t.length(); continue; }
                if(ControlFlowGraph.isGoto(t.opcode()) && t.branchTargets().length>0) { target=t.branchTargets()[0]; continue; }
                break;
            }
            if(target!=original) result.put(in.offset(),target);
        }
        return result;
    }

    private static Set<Integer> findDeadStores(ControlFlowGraph cfg, Set<Integer> unreachable) {
        Map<ControlFlowGraph.BasicBlock, Set<Integer>> use=new HashMap<>(), def=new HashMap<>(), liveIn=new HashMap<>(), liveOut=new HashMap<>();
        for(ControlFlowGraph.BasicBlock b:cfg.blocks()) {
            LinkedHashSet<Integer> u=new LinkedHashSet<>(), d=new LinkedHashSet<>();
            for(Instruction i:b.instructions()) {
                if(unreachable.contains(i.offset())) continue;
                Integer load=loadSlot(i); if(load!=null && !d.contains(load)) u.add(load);
                Integer store=storeSlot(i); if(store!=null) d.add(store);
                if(i.opcode()==132) { int x=i.u1(0); if(!d.contains(x)) u.add(x); d.add(x); }
            }
            use.put(b,u); def.put(b,d); liveIn.put(b,new LinkedHashSet<>()); liveOut.put(b,new LinkedHashSet<>());
        }
        boolean changed;
        do {
            changed=false;
            List<ControlFlowGraph.BasicBlock> bs=cfg.blocks();
            for(int n=bs.size()-1;n>=0;n--) {
                ControlFlowGraph.BasicBlock b=bs.get(n); LinkedHashSet<Integer> out=new LinkedHashSet<>();
                for(ControlFlowGraph.BasicBlock s:b.successors()) out.addAll(liveIn.get(s));
                for(ControlFlowGraph.BasicBlock s:b.exceptionalSuccessors()) out.addAll(liveIn.get(s));
                LinkedHashSet<Integer> in=new LinkedHashSet<>(out); in.removeAll(def.get(b)); in.addAll(use.get(b));
                if(!out.equals(liveOut.get(b)) || !in.equals(liveIn.get(b))) { liveOut.put(b,out); liveIn.put(b,in); changed=true; }
            }
        } while(changed);
        LinkedHashSet<Integer> deadStores=new LinkedHashSet<>();
        for(ControlFlowGraph.BasicBlock b:cfg.blocks()) {
            LinkedHashSet<Integer> live=new LinkedHashSet<>(liveOut.get(b)); List<Instruction> xs=b.instructions();
            for(int n=xs.size()-1;n>=0;n--) {
                Instruction i=xs.get(n); if(unreachable.contains(i.offset())) continue;
                Integer store=storeSlot(i);
                if(store!=null) { if(!live.contains(store)) deadStores.add(i.offset()); live.remove(store); }
                Integer load=loadSlot(i); if(load!=null) live.add(load);
                if(i.opcode()==132) live.add(i.u1(0));
            }
        }
        return deadStores;
    }

    private static Integer loadSlot(Instruction i) {
        int op=i.opcode(); if(op>=21&&op<=25) return i.u1(0);
        if(op>=26&&op<=29) return op-26; if(op>=30&&op<=33)return op-30; if(op>=34&&op<=37)return op-34;
        if(op>=38&&op<=41)return op-38; if(op>=42&&op<=45)return op-42;
        if(op==196&&i.operands().length>=3&&i.u1(0)>=21&&i.u1(0)<=25)return i.u2(1); return null;
    }
    private static Integer storeSlot(Instruction i) {
        int op=i.opcode(); if(op>=54&&op<=58)return i.u1(0);
        if(op>=59&&op<=62)return op-59; if(op>=63&&op<=66)return op-63; if(op>=67&&op<=70)return op-67;
        if(op>=71&&op<=74)return op-71; if(op>=75&&op<=78)return op-75;
        if(op==196&&i.operands().length>=3&&i.u1(0)>=54&&i.u1(0)<=58)return i.u2(1); return null;
    }

    private static boolean locallyFoldable(List<Instruction> insns,int i) {
        if(i<2)return false; return isConstPush(insns.get(i-1))&&isConstPush(insns.get(i-2));
    }
    private static boolean isConstPush(Instruction i){int op=i.opcode();return (op>=1&&op<=20);}
    private static boolean isArithmetic(int op){return op>=96&&op<=152;}

    private static void arithmetic(Frame f, char op) {
        Object b=f.pop(), a=f.pop(); if(!(a instanceof Number x)||!(b instanceof Number y)){f.push(UNKNOWN);return;}
        try {
            if(a instanceof Double||b instanceof Double){double p=x.doubleValue(),q=y.doubleValue();f.push(switch(op){case '+'->p+q;case '-'->p-q;case '*'->p*q;case '/'->p/q;case '%'->p%q;default->UNKNOWN;});return;}
            if(a instanceof Float||b instanceof Float){float p=x.floatValue(),q=y.floatValue();f.push(switch(op){case '+'->p+q;case '-'->p-q;case '*'->p*q;case '/'->p/q;case '%'->p%q;default->UNKNOWN;});return;}
            long p=x.longValue(),q=y.longValue(); Object r=switch(op){case '+'->p+q;case '-'->p-q;case '*'->p*q;case '/'->q==0?UNKNOWN:p/q;case '%'->q==0?UNKNOWN:p%q;case '&'->p&q;case '|'->p|q;case '^'->p^q;case '<'->p<<(q&63);case '>'->p>>(q&63);case 'u'->p>>>(q&63);default->UNKNOWN;};
            f.push((a instanceof Integer&&b instanceof Integer&&r instanceof Long l)?l.intValue():r);
        } catch(RuntimeException ex){f.push(UNKNOWN);}
    }
    private static void unaryNeg(Frame f){Object a=f.pop();if(a instanceof Integer x)f.push(-x);else if(a instanceof Long x)f.push(-x);else if(a instanceof Float x)f.push(-x);else if(a instanceof Double x)f.push(-x);else f.push(UNKNOWN);}
    private static void convert(int op,Frame f){Object a=f.pop();if(!(a instanceof Number n)){f.push(UNKNOWN);return;}f.push(switch(op){case 133,140,143->n.longValue();case 134,137,144->n.floatValue();case 135,138,141->n.doubleValue();case 136,139,142,145,146,147->n.intValue();default->UNKNOWN;});}
    private static void compare(int op,Frame f){Object b=f.pop(),a=f.pop();if(!(a instanceof Number x)||!(b instanceof Number y)){f.push(UNKNOWN);return;}double p=x.doubleValue(),q=y.doubleValue();f.push(Double.compare(p,q));}

    private static final class Frame {
        final Object[] locals; final ArrayList<Object> stack=new ArrayList<>();
        Frame(int n){locals=new Object[Math.max(0,n)];Arrays.fill(locals,UNKNOWN);} Frame(Object[] l,List<Object>s){locals=l;stack.addAll(s);}
        Frame copy(){return new Frame(locals.clone(),stack);} Object local(int i){return i>=0&&i<locals.length?locals[i]:UNKNOWN;} void setLocal(int i,Object v){if(i>=0&&i<locals.length)locals[i]=normalize(v);} void push(Object v){stack.add(normalize(v));}
        Object pop(){return stack.isEmpty()?UNKNOWN:stack.remove(stack.size()-1);} Object peek(){return peek(0);} Object peek(int depth){int i=stack.size()-1-depth;return i<0?UNKNOWN:stack.get(i);} void clearStack(){stack.clear();}
        static Object normalize(Object v){return v==null?NULL:v;}
        static Frame merge(Frame a,Frame b){Object[] l=new Object[Math.max(a.locals.length,b.locals.length)];for(int i=0;i<l.length;i++){Object x=i<a.locals.length?a.locals[i]:UNKNOWN,y=i<b.locals.length?b.locals[i]:UNKNOWN;l[i]=eq(x,y)?x:UNKNOWN;}ArrayList<Object>s=new ArrayList<>();if(a.stack.size()==b.stack.size())for(int i=0;i<a.stack.size();i++)s.add(eq(a.stack.get(i),b.stack.get(i))?a.stack.get(i):UNKNOWN);return new Frame(l,s);}
        static boolean eq(Object a,Object b){return a==b||Objects.equals(a,b);}
        @Override public boolean equals(Object o){return o instanceof Frame f&&Arrays.equals(locals,f.locals)&&stack.equals(f.stack);}@Override public int hashCode(){return Arrays.hashCode(locals)*31+stack.hashCode();}
    }
}
