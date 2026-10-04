package dev.aegis.classfile;

import java.util.*;

public record ClassFile(int minorVersion, int majorVersion, ConstantPool constantPool,
                        int accessFlags, String thisClass, String superClass,
                        List<String> interfaces, List<MemberInfo> fields,
                        List<MemberInfo> methods, List<AttributeInfo> attributes,
                        byte[] originalBytes) {
    public int javaVersion() {
        return majorVersion >= 45 ? majorVersion - 44 : -1;
    }

    public AttributeInfo attribute(String name) {
        for (AttributeInfo a : attributes) if (a.name().equals(name)) return a;
        return null;
    }

    public boolean hasAttribute(String name) { return attribute(name) != null; }
}
