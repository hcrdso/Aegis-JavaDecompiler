package dev.aegis.classfile;

import java.util.*;

public final class LocalVariableTable {
    public record Local(int startPc,int length,String name,String descriptor,int slot){
        public boolean covers(int pc){return pc>=startPc&&pc<startPc+length;}
    }
    private final List<Local> locals;
    private LocalVariableTable(List<Local> locals){this.locals=List.copyOf(locals);}
    public static LocalVariableTable from(CodeAttribute code, ConstantPool cp){
        AttributeInfo a=code.attribute("LocalVariableTable"); if(a==null)return new LocalVariableTable(List.of());
        try { ByteReader r=a.reader(); int n=r.u2(); ArrayList<Local> out=new ArrayList<>(n);
            for(int i=0;i<n;i++){int start=r.u2(),len=r.u2();String name=cp.utf8(r.u2()),desc=cp.utf8(r.u2());int slot=r.u2();out.add(new Local(start,len,name,desc,slot));}
            return new LocalVariableTable(out);
        } catch(RuntimeException ex){return new LocalVariableTable(List.of());}
    }
    public Local find(int slot,int pc){
        Local best=null; for(Local l:locals)if(l.slot()==slot&&l.covers(pc)){if(best==null||l.startPc()>best.startPc())best=l;}return best;
    }
    public List<Local> all(){return locals;}
}
