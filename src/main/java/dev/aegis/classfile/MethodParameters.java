package dev.aegis.classfile;

import java.util.*;

/** MethodParameters attribute (Java 8+). */
public final class MethodParameters {
    public record Parameter(String name, int accessFlags) {
        public boolean isFinal() { return (accessFlags & 0x0010) != 0; }
        public boolean isSynthetic() { return (accessFlags & 0x1000) != 0; }
        public boolean isMandated() { return (accessFlags & 0x8000) != 0; }
    }

    private final List<Parameter> parameters;
    private MethodParameters(List<Parameter> parameters) { this.parameters = List.copyOf(parameters); }

    public static MethodParameters from(MemberInfo method, ConstantPool cp) {
        AttributeInfo a = method.attribute("MethodParameters");
        if (a == null) return new MethodParameters(List.of());
        try {
            ByteReader r = a.reader();
            int n = r.u1();
            ArrayList<Parameter> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                int nameIndex = r.u2();
                int flags = r.u2();
                out.add(new Parameter(nameIndex == 0 ? null : cp.utf8(nameIndex), flags));
            }
            return new MethodParameters(out);
        } catch (RuntimeException ex) {
            return new MethodParameters(List.of());
        }
    }

    public Parameter get(int index) { return index >= 0 && index < parameters.size() ? parameters.get(index) : null; }
    public List<Parameter> all() { return parameters; }
}
