package dev.aegis.deobfuscate;

import dev.aegis.cfg.ControlFlowGraph;
import dev.aegis.classfile.*;
import java.util.*;

/** Conservative in-place bytecode cleaner. Code length never changes, so existing metadata offsets stay stable. */
public final class NativeBytecodeCleaner {
    public record CleanResult(byte[] bytes, int methodsChanged, int instructionsRewritten, List<String> notes) {}

    public CleanResult clean(byte[] classBytes) {
        ClassFile cf = ClassFileParser.parse(classBytes);
        byte[] out = classBytes.clone();
        ArrayList<String> notes = new ArrayList<>();
        int[] stats = new int[2]; // methods, instructions
        Cursor c = new Cursor(out);
        if (c.u4() != 0xCAFEBABEL) throw new IllegalArgumentException("Not a classfile");
        c.skip(4); // minor + major
        skipConstantPool(c);
        c.skip(6); // access,this,super
        int ifaces = c.u2(); c.skip(ifaces * 2);
        int fields = c.u2(); for (int i=0;i<fields;i++) skipMember(c);
        int methods = c.u2();
        for (int mi=0;mi<methods;mi++) {
            c.u2(); int nameIndex=c.u2(), descIndex=c.u2(); int ac=c.u2();
            MemberInfo member = mi < cf.methods().size() ? cf.methods().get(mi) : null;
            String scannedName = safeUtf(cf.constantPool(), nameIndex), scannedDesc = safeUtf(cf.constantPool(), descIndex);
            for (int ai=0;ai<ac;ai++) {
                int attrNameIndex=c.u2(); long len=c.u4(); int dataStart=c.pos;
                String attrName=safeUtf(cf.constantPool(),attrNameIndex);
                if ("Code".equals(attrName) && member != null && Objects.equals(member.name(),scannedName) && Objects.equals(member.descriptor(),scannedDesc)) {
                    int p=dataStart; if(len>=8){int codeLen=(int)u4(out,p+4);int codeStart=p+8;if(codeLen>=0&&codeStart+codeLen<=out.length){
                        try {
                            CodeAttribute code=CodeAttribute.parse(member.attribute("Code"),cf.constantPool());
                            DeobfuscationResult plan=new Deobfuscator().analyze(cf,member,code);
                            int changed=patchCode(out,codeStart,codeLen,plan);
                            if(changed>0){stats[0]++;stats[1]+=changed;notes.add(member.name()+member.descriptor()+": "+changed+" rewrite(s), "+plan.report().compact());}
                        } catch(RuntimeException ex){notes.add(member.name()+member.descriptor()+": skipped cleaner ("+safe(ex.getMessage())+")");}
                    }}
                }
                c.pos=dataStart+checkedLen(len,out.length-dataStart);
            }
        }
        return new CleanResult(out,stats[0],stats[1],List.copyOf(notes));
    }

    private static int patchCode(byte[] out,int base,int codeLen,DeobfuscationResult plan){
        int changed=0;List<Instruction> xs=plan.instructions();
        for(Instruction in:xs){int off=in.offset();if(off<0||off+in.length()>codeLen)continue;int p=base+off;
            Integer forcedSwitch=plan.forcedSwitchTarget(off);
            if(forcedSwitch!=null&&(in.opcode()==170||in.opcode()==171)){
                // Keep the original switch opcode and size so StackMapTable/verification stay valid.
                // Redirect every encoded edge to the proven target; the switch still consumes its key.
                if(patchSwitchEdges(out,base,in,forcedSwitch)){changed++;continue;}
            }
            Boolean forced=plan.forcedBranch(off);
            if(forced!=null&&!forced&&ControlFlowGraph.isConditional(in.opcode())){
                // Preserve the original conditional's stack consumption without changing code length.
                int popOpcode=(in.opcode()>=159&&in.opcode()<=166)?88:87;
                out[p]=(byte)popOpcode;for(int k=1;k<in.length();k++)out[p+k]=0;changed++;continue;
            }
            if(plan.deadStoreOffsets().contains(off)&&isStore(in)){
                out[p]=(byte)(isWideValueStore(in)?88:87);for(int k=1;k<in.length();k++)out[p+k]=0;changed++;continue;
            }
            if(ControlFlowGraph.isGoto(in.opcode())&&in.branchTargets().length>0){int target=plan.threadedTarget(off,in.branchTargets()[0]);int next=off+in.length();if(target==next){fillNop(out,p,in.length());changed++;continue;}if(target!=in.branchTargets()[0]){
                    int delta=target-off;if(in.opcode()==167&&delta>=Short.MIN_VALUE&&delta<=Short.MAX_VALUE){out[p+1]=(byte)(delta>>>8);out[p+2]=(byte)delta;changed++;}
                    else if(in.opcode()==200){out[p+1]=(byte)(delta>>>24);out[p+2]=(byte)(delta>>>16);out[p+3]=(byte)(delta>>>8);out[p+4]=(byte)delta;changed++;}
                }}
        }
        return changed;
    }
    private static boolean patchSwitchEdges(byte[] out,int base,Instruction in,int target){
        byte[] ops=in.operands();
        int pad=(4-((in.offset()+1)&3))&3;
        int cursor=pad;
        int delta=target-in.offset();
        try{
            // operands exclude the opcode but include alignment padding.
            writeS4(out,base+in.offset()+1+cursor,delta); cursor+=4; // default
            if(in.opcode()==170){
                int low=s4(ops,cursor); cursor+=4; int high=s4(ops,cursor); cursor+=4;
                long n=(long)high-low+1; if(n<0||n>1_000_000)return false;
                for(int i=0;i<n;i++){writeS4(out,base+in.offset()+1+cursor,delta);cursor+=4;}
            } else {
                int n=s4(ops,cursor); cursor+=4; if(n<0||n>1_000_000)return false;
                for(int i=0;i<n;i++){cursor+=4;writeS4(out,base+in.offset()+1+cursor,delta);cursor+=4;}
            }
            return true;
        }catch(RuntimeException ex){return false;}
    }
    private static int s4(byte[] b,int p){return ((b[p]&255)<<24)|((b[p+1]&255)<<16)|((b[p+2]&255)<<8)|(b[p+3]&255);}
    private static void writeS4(byte[] a,int p,int v){a[p]=(byte)(v>>>24);a[p+1]=(byte)(v>>>16);a[p+2]=(byte)(v>>>8);a[p+3]=(byte)v;}

    private static boolean isStore(Instruction i){int op=i.opcode();return(op>=54&&op<=78)||(op==196&&i.operands().length>0&&i.u1(0)>=54&&i.u1(0)<=58);}
    private static boolean isWideValueStore(Instruction i){int op=i.opcode();if(op==55||op==57||(op>=63&&op<=66)||(op>=71&&op<=74))return true;if(op==196&&i.operands().length>0){int w=i.u1(0);return w==55||w==57;}return false;}
    private static void fillNop(byte[]a,int p,int n){Arrays.fill(a,p,p+n,(byte)0);}

    private static void skipConstantPool(Cursor c){int count=c.u2();for(int i=1;i<count;i++){int tag=c.u1();switch(tag){case 1->{int n=c.u2();c.skip(n);}case 3,4->c.skip(4);case 5,6->{c.skip(8);i++;}case 7,8,16,19,20->c.skip(2);case 9,10,11,12,17,18->c.skip(4);case 15->c.skip(3);default->throw new IllegalArgumentException("Bad constant-pool tag "+tag);}}}
    private static void skipMember(Cursor c){c.skip(6);int ac=c.u2();for(int i=0;i<ac;i++){c.skip(2);long len=c.u4();c.skip(checkedLen(len,c.data.length-c.pos));}}
    private static int checkedLen(long len,int remaining){if(len<0||len>Integer.MAX_VALUE||len>remaining)throw new IllegalArgumentException("Invalid attribute length");return(int)len;}
    private static String safeUtf(ConstantPool cp,int idx){try{return cp.utf8(idx);}catch(RuntimeException ex){return "";}}
    private static long u4(byte[]a,int p){return((long)(a[p]&255)<<24)|((long)(a[p+1]&255)<<16)|((long)(a[p+2]&255)<<8)|(a[p+3]&255L);}
    private static String safe(String s){return s==null?"unknown":s.replace('\n',' ').replace('\r',' ');}

    private static final class Cursor{final byte[]data;int pos;Cursor(byte[]d){data=d;}int u1(){ensure(1);return data[pos++]&255;}int u2(){ensure(2);int v=((data[pos]&255)<<8)|(data[pos+1]&255);pos+=2;return v;}long u4(){ensure(4);long v=NativeBytecodeCleaner.u4(data,pos);pos+=4;return v;}void skip(int n){ensure(n);pos+=n;}void ensure(int n){if(n<0||pos+n>data.length)throw new IllegalArgumentException("Truncated classfile");}}
}
