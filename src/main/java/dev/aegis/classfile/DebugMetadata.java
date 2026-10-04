package dev.aegis.classfile;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Reads source/debug metadata that can survive javac/other JVM compilers.
 * This does not claim to recover source comments: Java comments are not stored in normal classfiles.
 */
public final class DebugMetadata {
    private DebugMetadata() {}

    public static String sourceFile(ClassFile cf) {
        AttributeInfo a = cf.attribute("SourceFile");
        if (a == null || a.data().length != 2) return null;
        try { return cf.constantPool().utf8(a.reader().u2()); }
        catch (RuntimeException ex) { return null; }
    }

    public static String sourceDebugExtension(ClassFile cf) {
        AttributeInfo a = cf.attribute("SourceDebugExtension");
        if (a == null || a.data().length == 0) return null;
        try { return new String(a.data(), StandardCharsets.UTF_8); }
        catch (RuntimeException ex) { return null; }
    }

    public static List<String> smapSourceFiles(ClassFile cf) {
        String smap = sourceDebugExtension(cf);
        if (smap == null || !smap.startsWith("SMAP")) return List.of();
        LinkedHashSet<String> files = new LinkedHashSet<>();
        String[] lines = smap.replace("\r", "").split("\n");
        boolean inFiles = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.equals("*F")) { inFiles = true; continue; }
            if (line.startsWith("*")) { if (inFiles) break; continue; }
            if (!inFiles || line.isEmpty()) continue;
            if (line.startsWith("+")) {
                if (i + 1 < lines.length) {
                    String p = lines[++i].trim();
                    if (!p.isEmpty()) files.add(p);
                }
            } else {
                int sp = line.indexOf(' ');
                String p = sp >= 0 ? line.substring(sp + 1).trim() : line;
                if (!p.isEmpty()) files.add(p);
            }
        }
        return List.copyOf(files);
    }

    public static String genericSignature(MemberInfo member, ConstantPool cp) {
        AttributeInfo a = member.attribute("Signature");
        if (a == null || a.data().length != 2) return null;
        try { return cp.utf8(a.reader().u2()); }
        catch (RuntimeException ex) { return null; }
    }

    public static String genericSignature(ClassFile cf) {
        AttributeInfo a = cf.attribute("Signature");
        if (a == null || a.data().length != 2) return null;
        try { return cf.constantPool().utf8(a.reader().u2()); }
        catch (RuntimeException ex) { return null; }
    }

    public static int[] lineRange(ClassFile cf, MemberInfo method) {
        AttributeInfo ca = method.attribute("Code");
        if (ca == null) return new int[]{-1, -1};
        try {
            CodeAttribute code = CodeAttribute.parse(ca, cf.constantPool());
            LineNumberTable lnt = LineNumberTable.from(code);
            return new int[]{lnt.minLine(), lnt.maxLine()};
        } catch (RuntimeException ex) { return new int[]{-1, -1}; }
    }

    /** True when useful source-level debug information still exists. */
    public static boolean hasDebugInfo(ClassFile cf) {
        if (sourceFile(cf) != null || sourceDebugExtension(cf) != null) return true;
        for (MemberInfo m : cf.methods()) {
            AttributeInfo ca = m.attribute("Code");
            if (ca == null) continue;
            try {
                CodeAttribute code = CodeAttribute.parse(ca, cf.constantPool());
                if (code.attribute("LineNumberTable") != null || code.attribute("LocalVariableTable") != null || code.attribute("LocalVariableTypeTable") != null) return true;
            } catch (RuntimeException ignored) { }
        }
        return false;
    }
}
