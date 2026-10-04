package dev.aegis.classfile;

import java.util.*;

public final class BootstrapMethods {
    public record BootstrapMethod(int methodHandleIndex, int[] argumentIndices) {}
    private final List<BootstrapMethod> methods;
    private BootstrapMethods(List<BootstrapMethod> methods){this.methods=List.copyOf(methods);}

    public static BootstrapMethods from(ClassFile cf){
        AttributeInfo a=cf.attribute("BootstrapMethods");
        if(a==null)return new BootstrapMethods(List.of());
        ByteReader r=a.reader(); int n=r.u2(); ArrayList<BootstrapMethod> out=new ArrayList<>(n);
        for(int i=0;i<n;i++){int ref=r.u2(), argc=r.u2(); int[] args=new int[argc]; for(int j=0;j<argc;j++)args[j]=r.u2(); out.add(new BootstrapMethod(ref,args));}
        return new BootstrapMethods(out);
    }
    public BootstrapMethod get(int index){return index>=0&&index<methods.size()?methods.get(index):null;}
    public int size(){return methods.size();}
}
