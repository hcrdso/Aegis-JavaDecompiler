package dev.aegis.classfile;

import java.io.IOException;
import java.util.*;

public final class ClassFileParser {
    private ClassFileParser() {}

    public static ClassFile parse(byte[] bytes) {
        try {
            ByteReader in = new ByteReader(bytes);
            long magic = in.u4();
            if (magic != 0xCAFEBABEL) throw new IllegalArgumentException("Not a JVM classfile (CAFEBABE missing)");
            int minor = in.u2();
            int major = in.u2();
            ConstantPool cp = ConstantPool.read(in);
            int access = in.u2();
            int thisIndex = in.u2();
            int superIndex = in.u2();
            String thisClass = cp.className(thisIndex);
            String superClass = superIndex == 0 ? null : cp.className(superIndex);

            int ifaceCount = in.u2();
            ArrayList<String> interfaces = new ArrayList<>(ifaceCount);
            for (int i = 0; i < ifaceCount; i++) interfaces.add(cp.className(in.u2()));

            int fieldCount = in.u2();
            ArrayList<MemberInfo> fields = new ArrayList<>(fieldCount);
            for (int i = 0; i < fieldCount; i++) fields.add(readMember(in, cp));

            int methodCount = in.u2();
            ArrayList<MemberInfo> methods = new ArrayList<>(methodCount);
            for (int i = 0; i < methodCount; i++) methods.add(readMember(in, cp));

            int attrCount = in.u2();
            ArrayList<AttributeInfo> attrs = new ArrayList<>(attrCount);
            for (int i = 0; i < attrCount; i++) attrs.add(readAttribute(in, cp));
            if (in.remaining() != 0) throw new IllegalArgumentException("Trailing bytes after classfile: " + in.remaining());

            return new ClassFile(minor, major, cp, access, thisClass, superClass,
                    List.copyOf(interfaces), List.copyOf(fields), List.copyOf(methods), List.copyOf(attrs), bytes.clone());
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid modified UTF-8 in classfile", e);
        }
    }

    private static MemberInfo readMember(ByteReader in, ConstantPool cp) {
        int access = in.u2();
        String name = cp.utf8(in.u2());
        String descriptor = cp.utf8(in.u2());
        int attrCount = in.u2();
        ArrayList<AttributeInfo> attrs = new ArrayList<>(attrCount);
        for (int i = 0; i < attrCount; i++) attrs.add(readAttribute(in, cp));
        return new MemberInfo(access, name, descriptor, List.copyOf(attrs));
    }

    static AttributeInfo readAttribute(ByteReader in, ConstantPool cp) {
        String name = cp.utf8(in.u2());
        long len = in.u4();
        if (len > Integer.MAX_VALUE || len > in.remaining()) {
            throw new IllegalArgumentException("Invalid attribute length for " + name + ": " + len);
        }
        return new AttributeInfo(name, in.bytes((int) len));
    }
}
