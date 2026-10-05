package dev.aegis.rename;

import dev.aegis.classfile.*;
import java.util.*;



public final class ReflectionUsageAnalyzer {
    public record Result(Set<String> memberNames, Set<String> classNames, boolean dynamicReflection, List<String> notes) {}
    private record Value(String stringLiteral) { static final Value UNKNOWN=new Value(null); boolean isString(){return stringLiteral!=null;} }

    public Result scan(Collection<ClassFile> classes) {
        LinkedHashSet<String> members=new LinkedHashSet<>(), names=new LinkedHashSet<>();
        ArrayList<String> notes=new ArrayList<>();
        boolean[] dynamic={false};
        for(ClassFile cf:classes) for(MemberInfo m:cf.methods()) {
            AttributeInfo ca=m.attribute("Code"); if(ca==null)continue;
            try {
                CodeAttribute code=CodeAttribute.parse(ca,cf.constantPool());
                simulate(cf,m,BytecodeDecoder.decode(code.code()),members,names,notes,dynamic);
            } catch(RuntimeException ignored) { }
        }
        return new Result(Set.copyOf(members),Set.copyOf(names),dynamic[0],List.copyOf(notes));
    }

    private static void simulate(ClassFile cf, MemberInfo method, List<Instruction> xs,
                                 Set<String> members, Set<String> classes, List<String> notes, boolean[] dynamic) {
        ArrayList<Value> stack=new ArrayList<>();
        HashMap<Integer,Value> locals=new HashMap<>();
        for(Instruction in:xs){
            int op=in.opcode();
            try {
                if(op==1){push(stack,Value.UNKNOWN);continue;}
                if(op>=2&&op<=17){push(stack,Value.UNKNOWN);continue;}
                if(op==18||op==19||op==20){Object v=cf.constantPool().constant(op==18?in.u1(0):in.u2(0));push(stack,v instanceof String s?new Value(s):Value.UNKNOWN);continue;}

                Integer load=loadSlot(in); if(load!=null){push(stack,locals.getOrDefault(load,Value.UNKNOWN));continue;}
                Integer store=storeSlot(in); if(store!=null){locals.put(store,pop(stack));continue;}

                if(op>=46&&op<=53){pop(stack);pop(stack);push(stack,Value.UNKNOWN);continue;}
                if(op>=79&&op<=86){pop(stack);pop(stack);pop(stack);continue;}
                if(op==87){pop(stack);continue;} if(op==88){pop(stack);pop(stack);continue;}
                if(op==89){Value a=peek(stack);push(stack,a);continue;}
                if(op==90){Value a=pop(stack),b=pop(stack);push(stack,a);push(stack,b);push(stack,a);continue;}
                if(op==95){Value a=pop(stack),b=pop(stack);push(stack,a);push(stack,b);continue;}
                if(op>=96&&op<=131){pop(stack);pop(stack);push(stack,Value.UNKNOWN);continue;}
                if(op==132)continue;
                if(op>=133&&op<=147){pop(stack);push(stack,Value.UNKNOWN);continue;}
                if(op>=148&&op<=152){pop(stack);pop(stack);push(stack,Value.UNKNOWN);continue;}

                if(op>=153&&op<=158){pop(stack);stack.clear();locals.clear();continue;}
                if(op>=159&&op<=166){pop(stack);pop(stack);stack.clear();locals.clear();continue;}
                if(op==167||op==168||op==200||op==201){stack.clear();locals.clear();continue;}
                if(op==169){stack.clear();locals.clear();continue;}
                if(op==170||op==171){pop(stack);stack.clear();locals.clear();continue;}
                if(op>=172&&op<=176){pop(stack);stack.clear();continue;} if(op==177){stack.clear();continue;}

                if(op==178){push(stack,Value.UNKNOWN);continue;}
                if(op==179){pop(stack);continue;}
                if(op==180){pop(stack);push(stack,Value.UNKNOWN);continue;}
                if(op==181){pop(stack);pop(stack);continue;}

                if(op>=182&&op<=185){
                    ConstantPool.MemberRef r=cf.constantPool().memberRef(in.u2(0));
                    DescriptorParser.MethodDescriptor md=DescriptorParser.method(r.descriptor());
                    ArrayList<Value> args=new ArrayList<>();
                    for(int n=md.parameterTypes().size()-1;n>=0;n--)args.add(0,pop(stack));
                    if(op!=184)pop(stack); // receiver
                    inspectSensitiveCall(cf,method,r,args,members,classes,notes,dynamic);
                    if(!"void".equals(md.returnType()))push(stack,Value.UNKNOWN);
                    continue;
                }
                if(op==186){
                    ConstantPool.DynamicRef r=cf.constantPool().dynamicRef(in.u2(0));
                    DescriptorParser.MethodDescriptor md=DescriptorParser.method(r.descriptor());
                    for(int n=0;n<md.parameterTypes().size();n++)pop(stack);
                    if(!"void".equals(md.returnType()))push(stack,Value.UNKNOWN);
                    continue;
                }
                if(op==187){push(stack,Value.UNKNOWN);continue;}
                if(op==188||op==189){pop(stack);push(stack,Value.UNKNOWN);continue;}
                if(op==190){pop(stack);push(stack,Value.UNKNOWN);continue;}
                if(op==191){pop(stack);stack.clear();continue;}
                if(op==192)continue;
                if(op==193){pop(stack);push(stack,Value.UNKNOWN);continue;}
                if(op==194||op==195){pop(stack);continue;}
                if(op==197){int dims=in.u1(2);for(int n=0;n<dims;n++)pop(stack);push(stack,Value.UNKNOWN);continue;}
                if(op==198||op==199){pop(stack);stack.clear();locals.clear();continue;}
                if(op==196){stack.clear();locals.clear();continue;}
            } catch(RuntimeException ex){ stack.clear(); locals.clear(); }
        }
    }

    private static void inspectSensitiveCall(ClassFile cf,MemberInfo method,ConstantPool.MemberRef r,List<Value> args,
                                             Set<String> members,Set<String> classes,List<String> notes,boolean[] dynamic){
        String kind=null; Value literal=null;
        if(r.owner().equals("java/lang/Class")&&r.name().equals("forName")){kind="class";literal=arg(args,0);}
        else if(r.owner().equals("java/lang/ClassLoader")&&r.name().equals("loadClass")){kind="class";literal=arg(args,0);}
        else if(r.owner().equals("java/lang/Class")&&Set.of("getMethod","getDeclaredMethod","getField","getDeclaredField").contains(r.name())){kind="member";literal=arg(args,0);}
        else if(r.owner().equals("java/lang/invoke/MethodHandles$Lookup")&&r.name().startsWith("find")){kind="member";literal=firstString(args);}
        else if(Set.of("java/util/concurrent/atomic/AtomicIntegerFieldUpdater","java/util/concurrent/atomic/AtomicLongFieldUpdater","java/util/concurrent/atomic/AtomicReferenceFieldUpdater").contains(r.owner())&&r.name().equals("newUpdater")){kind="member";literal=firstString(args);}
        if(kind==null)return;
        String s=literal!=null?literal.stringLiteral():null;
        if(s==null){dynamic[0]=true;notes.add(location(cf,method)+" uses name-sensitive API: "+r.owner()+"."+r.name()+" (dynamic argument)");return;}
        if(kind.equals("class"))classes.add(s.replace('.','/')); else members.add(s);
        notes.add(location(cf,method)+" uses name-sensitive API: "+r.owner()+"."+r.name()+" (literal=\""+s+"\")");
    }

    private static String location(ClassFile cf,MemberInfo m){return cf.thisClass()+"."+m.name()+m.descriptor();}
    private static Value arg(List<Value> args,int index){return index>=0&&index<args.size()?args.get(index):Value.UNKNOWN;}
    private static Value firstString(List<Value> args){for(Value v:args)if(v!=null&&v.isString())return v;return Value.UNKNOWN;}
    private static Value peek(List<Value> s){return s.isEmpty()?Value.UNKNOWN:s.get(s.size()-1);}
    private static Value pop(List<Value> s){return s.isEmpty()?Value.UNKNOWN:s.remove(s.size()-1);}
    private static void push(List<Value> s,Value v){s.add(v==null?Value.UNKNOWN:v);}

    private static Integer loadSlot(Instruction i){int op=i.opcode();if(op>=21&&op<=25)return i.u1(0);if(op>=26&&op<=45)return switch(op){case 26,30,34,38,42->0;case 27,31,35,39,43->1;case 28,32,36,40,44->2;default->3;};return null;}
    private static Integer storeSlot(Instruction i){int op=i.opcode();if(op>=54&&op<=58)return i.u1(0);if(op>=59&&op<=78)return switch(op){case 59,63,67,71,75->0;case 60,64,68,72,76->1;case 61,65,69,73,77->2;default->3;};return null;}
}
