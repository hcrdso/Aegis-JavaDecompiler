package dev.aegis.classfile;

import java.util.*;

/** Parses the Java 16+ Record attribute without external classfile libraries */
public final class RecordComponents {
    public record Component(String name,String descriptor,String genericSignature) {}
    private RecordComponents() {}

    public static List<Component> from(ClassFile cf){
        AttributeInfo a=cf.attribute("Record"); if(a==null)return List.of();
        try{
            ByteReader r=a.reader();int count=r.u2();ArrayList<Component> out=new ArrayList<>(count);
            for(int i=0;i<count;i++){
                String name=cf.constantPool().utf8(r.u2());String descriptor=cf.constantPool().utf8(r.u2());
                int ac=r.u2();String signature=null;
                for(int j=0;j<ac;j++){
                    String an=cf.constantPool().utf8(r.u2());long len=r.u4();if(len>Integer.MAX_VALUE||len>r.remaining())throw new IllegalArgumentException("Invalid record component attribute");
                    byte[] data=r.bytes((int)len);
                    if(an.equals("Signature")&&data.length==2){ByteReader sr=new ByteReader(data);signature=cf.constantPool().utf8(sr.u2());}
                }
                out.add(new Component(name,descriptor,signature));
            }
            return List.copyOf(out);
        }catch(RuntimeException ex){return List.of();}
    }
}
