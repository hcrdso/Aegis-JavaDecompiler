package dev.aegis.deobfuscate;

import dev.aegis.classfile.*;
import java.util.*;

/**
 * Sandboxed constant evaluator for small pure methods. It interprets a safe JVM subset and
 * only emulates whitelisted java.lang operations; target bytecode is never loaded or executed.
 */
public final class ConstantMethodEvaluator {
    private static final int MAX_STEPS = 50_000;
    private record NewRef(String type) {}

    public Optional<Object> evaluate(ClassFile cf, String name, String descriptor, List<Object> args) {
        return evaluate(cf,name,descriptor,args,0);
    }

    private Optional<Object> evaluate(ClassFile cf,String name,String descriptor,List<Object> args,int depth){
        if(depth>3)return Optional.empty();
        MemberInfo method=null;for(MemberInfo m:cf.methods())if(m.name().equals(name)&&m.descriptor().equals(descriptor)){method=m;break;}
        if(method==null||!AccessFlags.has(method.accessFlags(),AccessFlags.STATIC)||method.attribute("Code")==null)return Optional.empty();
        DescriptorParser.MethodDescriptor md;try{md=DescriptorParser.method(descriptor);}catch(RuntimeException ex){return Optional.empty();}
        if(md.parameterTypes().size()!=args.size())return Optional.empty();
        CodeAttribute code;try{code=CodeAttribute.parse(method.attribute("Code"),cf.constantPool());}catch(RuntimeException ex){return Optional.empty();}
        List<Instruction> xs;try{xs=BytecodeDecoder.decode(code.code());}catch(RuntimeException ex){return Optional.empty();}
        Map<Integer,Integer> byOffset=new HashMap<>();for(int i=0;i<xs.size();i++)byOffset.put(xs.get(i).offset(),i);
        Object[] locals=new Object[Math.max(code.maxLocals(),8)];int slot=0;for(int i=0;i<args.size();i++){locals[slot]=args.get(i);slot+=md.slotWidths().get(i);}ArrayList<Object> stack=new ArrayList<>();
        int pc=0,steps=0;
        try{
            while(pc>=0&&pc<xs.size()&&steps++<MAX_STEPS){Instruction in=xs.get(pc);int op=in.opcode();
                switch(op){
                    case 0 -> pc++;
                    case 1 -> {stack.add(null);pc++;}
                    case 2 -> {stack.add(-1);pc++;}case 3,4,5,6,7,8 -> {stack.add(op-3);pc++;}
                    case 9,10 -> {stack.add((long)(op-9));pc++;}case 11,12,13 -> {stack.add((float)(op-11));pc++;}case 14,15 -> {stack.add((double)(op-14));pc++;}
                    case 16 -> {stack.add((int)in.s1(0));pc++;}case 17 -> {stack.add((int)in.s2(0));pc++;}case 18 -> {stack.add(simpleConst(cf,in.u1(0)));pc++;}case 19,20 -> {stack.add(simpleConst(cf,in.u2(0)));pc++;}
                    case 21,22,23,24,25 -> {stack.add(locals[in.u1(0)]);pc++;}case 26,27,28,29 -> {stack.add(locals[op-26]);pc++;}case 30,31,32,33 -> {stack.add(locals[op-30]);pc++;}case 34,35,36,37 -> {stack.add(locals[op-34]);pc++;}case 38,39,40,41 -> {stack.add(locals[op-38]);pc++;}case 42,43,44,45 -> {stack.add(locals[op-42]);pc++;}
                    case 46,47,48,49,50,51,52,53 -> {int ix=intValue(pop(stack));Object arr=pop(stack);stack.add(arrayGet(arr,ix));pc++;}
                    case 54,55,56,57,58 -> {locals[in.u1(0)]=pop(stack);pc++;}case 59,60,61,62 -> {locals[op-59]=pop(stack);pc++;}case 63,64,65,66 -> {locals[op-63]=pop(stack);pc++;}case 67,68,69,70 -> {locals[op-67]=pop(stack);pc++;}case 71,72,73,74 -> {locals[op-71]=pop(stack);pc++;}case 75,76,77,78 -> {locals[op-75]=pop(stack);pc++;}
                    case 79,80,81,82,83,84,85,86 -> {Object v=pop(stack);int ix=intValue(pop(stack));Object arr=pop(stack);arraySet(arr,ix,v);pc++;}
                    case 87 -> {pop(stack);pc++;}case 88 -> {pop(stack);pop(stack);pc++;}case 89 -> {stack.add(peek(stack));pc++;}case 90 -> {Object a=pop(stack),b=pop(stack);stack.add(a);stack.add(b);stack.add(a);pc++;}case 95 -> {Object a=pop(stack),b=pop(stack);stack.add(a);stack.add(b);pc++;}
                    case 96,97,98,99 -> {numeric(stack,'+');pc++;}case 100,101,102,103 -> {numeric(stack,'-');pc++;}case 104,105,106,107 -> {numeric(stack,'*');pc++;}case 108,109,110,111 -> {numeric(stack,'/');pc++;}case 112,113,114,115 -> {numeric(stack,'%');pc++;}case 116,117,118,119 -> {Object a=pop(stack);stack.add(neg(a));pc++;}
                    case 120,121 -> {numeric(stack,'<');pc++;}case 122,123 -> {numeric(stack,'>');pc++;}case 124,125 -> {numeric(stack,'u');pc++;}case 126,127 -> {numeric(stack,'&');pc++;}case 128,129 -> {numeric(stack,'|');pc++;}case 130,131 -> {numeric(stack,'^');pc++;}
                    case 132 -> {int ix=in.u1(0);locals[ix]=intValue(locals[ix])+in.s1(1);pc++;}
                    case 133,134,135,136,137,138,139,140,141,142,143,144,145,146,147 -> {stack.add(convert(op,pop(stack)));pc++;}
                    case 148,149,150,151,152 -> {Number b=(Number)pop(stack),a=(Number)pop(stack);stack.add(Double.compare(a.doubleValue(),b.doubleValue()));pc++;}
                    case 153,154,155,156,157,158 -> {long a=((Number)pop(stack)).longValue();boolean take=switch(op){case 153->a==0;case 154->a!=0;case 155->a<0;case 156->a>=0;case 157->a>0;default->a<=0;};pc=take?jump(byOffset,in.branchTargets()[0]):pc+1;}
                    case 159,160,161,162,163,164 -> {long b=((Number)pop(stack)).longValue(),a=((Number)pop(stack)).longValue();boolean take=switch(op){case 159->a==b;case 160->a!=b;case 161->a<b;case 162->a>=b;case 163->a>b;default->a<=b;};pc=take?jump(byOffset,in.branchTargets()[0]):pc+1;}
                    case 165,166 -> {Object b=pop(stack),a=pop(stack);boolean eq=a==b||Objects.equals(a,b);pc=((op==165)==eq)?jump(byOffset,in.branchTargets()[0]):pc+1;}
                    case 167,200 -> pc=jump(byOffset,in.branchTargets()[0]);
                    case 170,171 -> {int key=intValue(pop(stack));int target=switchTarget(in,key);pc=jump(byOffset,target);}
                    case 172,173,174,175,176 -> {Object r=pop(stack);return isSupportedResult(r)?Optional.of(r):Optional.empty();}case 177 -> {return Optional.empty();}
                    case 178,179,180,181 -> {return Optional.empty();}
                    case 182,183,184,185 -> {if(!emulateInvoke(cf,in,op,stack,depth))return Optional.empty();pc++;}
                    case 186 -> {return Optional.empty();}
                    case 187 -> {stack.add(new NewRef(cf.constantPool().className(in.u2(0))));pc++;}
                    case 188 -> {int n=intValue(pop(stack));stack.add(newPrimitiveArray(in.u1(0),n));pc++;}case 189 -> {return Optional.empty();}
                    case 190 -> {stack.add(arrayLength(pop(stack)));pc++;}case 191 -> {return Optional.empty();}
                    case 192 -> pc++;case 193 -> {Object a=pop(stack);stack.add(a==null?0:1);pc++;}
                    case 198,199 -> {Object a=pop(stack);boolean take=op==198?a==null:a!=null;pc=take?jump(byOffset,in.branchTargets()[0]):pc+1;}
                    default -> {return Optional.empty();}
                }
            }
        }catch(RuntimeException ex){return Optional.empty();}
        return Optional.empty();
    }

    private boolean emulateInvoke(ClassFile cf,Instruction in,int op,ArrayList<Object> stack,int depth){
        ConstantPool.MemberRef r=cf.constantPool().memberRef(in.u2(0));DescriptorParser.MethodDescriptor md=DescriptorParser.method(r.descriptor());ArrayList<Object> args=new ArrayList<>();for(int i=md.parameterTypes().size()-1;i>=0;i--)args.add(0,pop(stack));Object receiver=op==184?null:pop(stack);
        if(op==184&&r.owner().equals(cf.thisClass())){Optional<Object> v=evaluate(cf,r.name(),r.descriptor(),args,depth+1);if(v.isEmpty())return false;if(!"void".equals(md.returnType()))stack.add(v.get());return true;}
        Object result;
        if("<init>".equals(r.name())&&receiver instanceof NewRef nrString&&nrString.type().equals("java/lang/String")&&args.size()==1&&args.get(0) instanceof char[] chars){String str=new String(chars);replaceIdentity(stack,receiver,str);return true;}
        if("java/lang/String".equals(r.owner())){
            if(!(receiver instanceof String strReceiver))return false;result=switch(r.name()){
                case "length" -> strReceiver.length();case "charAt" -> strReceiver.charAt(intValue(args.get(0)));case "toCharArray" -> strReceiver.toCharArray();case "substring" -> args.size()==1?strReceiver.substring(intValue(args.get(0))):strReceiver.substring(intValue(args.get(0)),intValue(args.get(1)));default -> null;};
            if(result==null)return false;if(!"void".equals(md.returnType()))stack.add(result);return true;
        }
        if("java/lang/StringBuilder".equals(r.owner())){
            if("<init>".equals(r.name())&&receiver instanceof NewRef nr&&nr.type().equals("java/lang/StringBuilder")){StringBuilder sb=new StringBuilder();if(args.size()==1&&args.get(0) instanceof String x)sb.append(x);replaceIdentity(stack,receiver,sb);return true;}
            if(!(receiver instanceof StringBuilder sb))return false;
            switch(r.name()){
                case "append" -> {Object a=args.get(0);if(a instanceof Character c)sb.append(c.charValue());else sb.append(a);result=sb;}
                case "toString" -> result=sb.toString();case "length" -> result=sb.length();default -> {return false;}
            }
            if(!"void".equals(md.returnType()))stack.add(result);return true;
        }
        if(op==184&&"java/lang/Integer".equals(r.owner())){if(args.size()!=1&&args.size()!=2)return false;result=switch(r.name()){case "rotateLeft"->Integer.rotateLeft(intValue(args.get(0)),intValue(args.get(1)));case "rotateRight"->Integer.rotateRight(intValue(args.get(0)),intValue(args.get(1)));case "reverse"->Integer.reverse(intValue(args.get(0)));case "reverseBytes"->Integer.reverseBytes(intValue(args.get(0)));default->null;};if(result==null)return false;stack.add(result);return true;}
        return false;
    }

    private static void replaceIdentity(List<Object> stack,Object old,Object replacement){for(int i=0;i<stack.size();i++)if(stack.get(i)==old)stack.set(i,replacement);}
    private static Object simpleConst(ClassFile cf,int i){Object x=cf.constantPool().constant(i);if(x instanceof String||x instanceof Number)return x;throw new IllegalArgumentException();}
    private static boolean isSupportedResult(Object x){return x instanceof String||x instanceof Integer||x instanceof Long||x instanceof Float||x instanceof Double||x instanceof Character||x instanceof Boolean;}
    private static int jump(Map<Integer,Integer> by,int off){Integer i=by.get(off);if(i==null)throw new IllegalArgumentException();return i;}
    private static Object pop(ArrayList<Object>s){if(s.isEmpty())throw new IllegalStateException();return s.remove(s.size()-1);}private static Object peek(ArrayList<Object>s){if(s.isEmpty())throw new IllegalStateException();return s.get(s.size()-1);}private static int intValue(Object x){return x instanceof Character c?c:(x instanceof Number n?n.intValue():(Integer)x);}
    private static Object neg(Object a){if(a instanceof Integer x)return-x;if(a instanceof Long x)return-x;if(a instanceof Float x)return-x;if(a instanceof Double x)return-x;throw new IllegalArgumentException();}
    private static void numeric(ArrayList<Object>s,char op){Object bo=pop(s),ao=pop(s);long x=longValue(ao),y=longValue(bo);Object r=switch(op){case '+'->x+y;case '-'->x-y;case '*'->x*y;case '/'->x/y;case '%'->x%y;case '&'->x&y;case '|'->x|y;case '^'->x^y;case '<'->x<<(y&63);case '>'->x>>(y&63);case 'u'->x>>>(y&63);default->throw new IllegalArgumentException();};s.add((ao instanceof Integer||ao instanceof Character)&&(bo instanceof Integer||bo instanceof Character)?(int)(long)r:r);}private static long longValue(Object x){if(x instanceof Character c)return c;if(x instanceof Number n)return n.longValue();throw new IllegalArgumentException();}
    private static Object convert(int op,Object a){if(!(a instanceof Number n))throw new IllegalArgumentException();return switch(op){case 133,140,143->n.longValue();case 134,137,144->n.floatValue();case 135,138,141->n.doubleValue();case 136,139,142->n.intValue();case 145->(int)(byte)n.intValue();case 146->(char)n.intValue();case 147->(int)(short)n.intValue();default->throw new IllegalArgumentException();};}
    private static Object newPrimitiveArray(int atype,int n){return switch(atype){case 4->new boolean[n];case 5->new char[n];case 6->new float[n];case 7->new double[n];case 8->new byte[n];case 9->new short[n];case 10->new int[n];case 11->new long[n];default->throw new IllegalArgumentException();};}
    private static int arrayLength(Object a){if(a instanceof char[]x)return x.length;if(a instanceof byte[]x)return x.length;if(a instanceof int[]x)return x.length;if(a instanceof short[]x)return x.length;if(a instanceof long[]x)return x.length;if(a instanceof boolean[]x)return x.length;if(a instanceof float[]x)return x.length;if(a instanceof double[]x)return x.length;throw new IllegalArgumentException();}
    private static Object arrayGet(Object a,int i){if(a instanceof char[]x)return x[i];if(a instanceof byte[]x)return(int)x[i];if(a instanceof int[]x)return x[i];if(a instanceof short[]x)return(int)x[i];if(a instanceof long[]x)return x[i];if(a instanceof boolean[]x)return x[i]?1:0;if(a instanceof float[]x)return x[i];if(a instanceof double[]x)return x[i];throw new IllegalArgumentException();}
    private static void arraySet(Object a,int i,Object v){if(a instanceof char[]x)x[i]=(char)intValue(v);else if(a instanceof byte[]x)x[i]=(byte)intValue(v);else if(a instanceof int[]x)x[i]=intValue(v);else if(a instanceof short[]x)x[i]=(short)intValue(v);else if(a instanceof long[]x)x[i]=((Number)v).longValue();else if(a instanceof boolean[]x)x[i]=intValue(v)!=0;else if(a instanceof float[]x)x[i]=((Number)v).floatValue();else if(a instanceof double[]x)x[i]=((Number)v).doubleValue();else throw new IllegalArgumentException();}
    private static int switchTarget(Instruction in,int key){byte[] b=in.operands();int pad=(4-((in.offset()+1)&3))&3;int p=pad;int def=in.offset()+s4(b,p);p+=4;if(in.opcode()==170){int low=s4(b,p);p+=4;int high=s4(b,p);p+=4;if(key<low||key>high)return def;p+=(key-low)*4;return in.offset()+s4(b,p);}int n=s4(b,p);p+=4;for(int i=0;i<n;i++){int k=s4(b,p);p+=4;int t=in.offset()+s4(b,p);p+=4;if(k==key)return t;}return def;}
    private static int s4(byte[]b,int p){return((b[p]&255)<<24)|((b[p+1]&255)<<16)|((b[p+2]&255)<<8)|(b[p+3]&255);}
}
