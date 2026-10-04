package dev.aegis.classfile;

import java.util.List;

public record MemberInfo(int accessFlags, String name, String descriptor, List<AttributeInfo> attributes) {
    public AttributeInfo attribute(String name) {
        for (AttributeInfo a : attributes) if (a.name().equals(name)) return a;
        return null;
    }
}
