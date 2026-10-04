package dev.aegis.workspace;

import dev.aegis.classfile.*;

public record ClassUnit(String entryName, String internalName, int majorVersion, byte[] bytes) {
    public static ClassUnit from(String entryName, byte[] bytes) {
        ClassFile cf = ClassFileParser.parse(bytes);
        return new ClassUnit(entryName, cf.thisClass(), cf.majorVersion(), bytes.clone());
    }
    public String binaryName() { return internalName.replace('/', '.'); }
    public String simpleName() {
        int slash = internalName.lastIndexOf('/');
        return slash < 0 ? internalName : internalName.substring(slash + 1);
    }
    public int javaVersion() { return majorVersion >= 45 ? majorVersion - 44 : -1; }
}
