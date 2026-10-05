package dev.aegis.classfile;

import java.util.*;

/** Small parsers for modern classfile attributes that affect Java source shape */
public final class ModernClassFeatures {
    private ModernClassFeatures() {}

    public static List<String> permittedSubclasses(ClassFile cf){
        AttributeInfo a=cf.attribute("PermittedSubclasses");if(a==null)return List.of();
        try{ByteReader r=a.reader();int n=r.u2();ArrayList<String> out=new ArrayList<>(n);for(int i=0;i<n;i++)out.add(cf.constantPool().className(r.u2()));return List.copyOf(out);}catch(RuntimeException ex){return List.of();}
    }
}
