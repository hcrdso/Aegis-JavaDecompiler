package dev.aegis.classfile;

import java.io.IOException;
import java.util.*;

public final class ConstantPool {
    public sealed interface Entry permits Utf8Entry, IntegerEntry, FloatEntry, LongEntry, DoubleEntry,
            ClassEntry, StringEntry, RefEntry, NameAndTypeEntry, MethodHandleEntry, MethodTypeEntry,
            DynamicEntry, ModuleEntry, PackageEntry {
        int tag();
    }

    public record Utf8Entry(String value) implements Entry { public int tag() { return 1; } }
    public record IntegerEntry(int value) implements Entry { public int tag() { return 3; } }
    public record FloatEntry(float value) implements Entry { public int tag() { return 4; } }
    public record LongEntry(long value) implements Entry { public int tag() { return 5; } }
    public record DoubleEntry(double value) implements Entry { public int tag() { return 6; } }
    public record ClassEntry(int nameIndex) implements Entry { public int tag() { return 7; } }
    public record StringEntry(int stringIndex) implements Entry { public int tag() { return 8; } }
    public record RefEntry(int tag, int classIndex, int nameAndTypeIndex) implements Entry {}
    public record NameAndTypeEntry(int nameIndex, int descriptorIndex) implements Entry { public int tag() { return 12; } }
    public record MethodHandleEntry(int referenceKind, int referenceIndex) implements Entry { public int tag() { return 15; } }
    public record MethodTypeEntry(int descriptorIndex) implements Entry { public int tag() { return 16; } }
    public record DynamicEntry(int tag, int bootstrapMethodIndex, int nameAndTypeIndex) implements Entry {}
    public record ModuleEntry(int nameIndex) implements Entry { public int tag() { return 19; } }
    public record PackageEntry(int nameIndex) implements Entry { public int tag() { return 20; } }

    public record NameAndType(String name, String descriptor) {}
    public record MemberRef(boolean isInterface, String owner, String name, String descriptor) {}
    public record DynamicRef(boolean invokeDynamic, int bootstrapMethodIndex, String name, String descriptor) {}

    private final Entry[] entries;

    private ConstantPool(Entry[] entries) {
        this.entries = entries;
    }

    public static ConstantPool read(ByteReader in) throws IOException {
        int count = in.u2();
        if (count <= 0) throw new IllegalArgumentException("Invalid constant_pool_count: " + count);
        Entry[] entries = new Entry[count];
        for (int i = 1; i < count; i++) {
            int tag = in.u1();
            entries[i] = switch (tag) {
                case 1 -> new Utf8Entry(in.modifiedUtf8());
                case 3 -> new IntegerEntry(in.s4());
                case 4 -> new FloatEntry(Float.intBitsToFloat(in.s4()));
                case 5 -> {
                    long hi = in.u4();
                    long lo = in.u4();
                    long value = (hi << 32) | lo;
                    yield new LongEntry(value);
                }
                case 6 -> {
                    long hi = in.u4();
                    long lo = in.u4();
                    long bits = (hi << 32) | lo;
                    yield new DoubleEntry(Double.longBitsToDouble(bits));
                }
                case 7 -> new ClassEntry(in.u2());
                case 8 -> new StringEntry(in.u2());
                case 9, 10, 11 -> new RefEntry(tag, in.u2(), in.u2());
                case 12 -> new NameAndTypeEntry(in.u2(), in.u2());
                case 15 -> new MethodHandleEntry(in.u1(), in.u2());
                case 16 -> new MethodTypeEntry(in.u2());
                case 17, 18 -> new DynamicEntry(tag, in.u2(), in.u2());
                case 19 -> new ModuleEntry(in.u2());
                case 20 -> new PackageEntry(in.u2());
                default -> throw new IllegalArgumentException("Unsupported constant-pool tag " + tag + " at #" + i);
            };
            if (tag == 5 || tag == 6) i++; // two CP slots
        }
        return new ConstantPool(entries);
    }

    public int size() { return entries.length; }

    public Entry entry(int index) {
        if (index <= 0 || index >= entries.length) throw new IllegalArgumentException("Invalid constant-pool index #" + index);
        Entry e = entries[index];
        if (e == null) throw new IllegalArgumentException("Invalid/unusable constant-pool slot #" + index);
        return e;
    }

    public String utf8(int index) {
        Entry e = entry(index);
        if (e instanceof Utf8Entry u) return u.value();
        throw wrong(index, "Utf8", e);
    }

    public String className(int index) {
        Entry e = entry(index);
        if (e instanceof ClassEntry c) return utf8(c.nameIndex());
        throw wrong(index, "Class", e);
    }

    public NameAndType nameAndType(int index) {
        Entry e = entry(index);
        if (e instanceof NameAndTypeEntry n) return new NameAndType(utf8(n.nameIndex()), utf8(n.descriptorIndex()));
        throw wrong(index, "NameAndType", e);
    }

    public MemberRef memberRef(int index) {
        Entry e = entry(index);
        if (e instanceof RefEntry r) {
            NameAndType nt = nameAndType(r.nameAndTypeIndex());
            return new MemberRef(r.tag() == 11, className(r.classIndex()), nt.name(), nt.descriptor());
        }
        throw wrong(index, "member ref", e);
    }

    public DynamicRef dynamicRef(int index) {
        Entry e = entry(index);
        if (e instanceof DynamicEntry d) {
            NameAndType nt = nameAndType(d.nameAndTypeIndex());
            return new DynamicRef(d.tag() == 18, d.bootstrapMethodIndex(), nt.name(), nt.descriptor());
        }
        throw wrong(index, "dynamic", e);
    }

    public Object constant(int index) {
        Entry e = entry(index);
        if (e instanceof IntegerEntry x) return x.value();
        if (e instanceof FloatEntry x) return x.value();
        if (e instanceof LongEntry x) return x.value();
        if (e instanceof DoubleEntry x) return x.value();
        if (e instanceof StringEntry x) return utf8(x.stringIndex());
        if (e instanceof ClassEntry x) return new ClassLiteral(utf8(x.nameIndex()));
        if (e instanceof MethodTypeEntry x) return new MethodTypeLiteral(utf8(x.descriptorIndex()));
        if (e instanceof MethodHandleEntry x) return x;
        if (e instanceof DynamicEntry) return dynamicRef(index);
        return e.toString();
    }

    public List<String> utf8Strings() {
        ArrayList<String> result = new ArrayList<>();
        for (int i = 1; i < entries.length; i++) {
            if (entries[i] instanceof Utf8Entry u) result.add(u.value());
        }
        return result;
    }

    public record ClassLiteral(String internalName) {}
    public record MethodTypeLiteral(String descriptor) {}

    private static IllegalArgumentException wrong(int index, String expected, Entry actual) {
        return new IllegalArgumentException("Constant-pool #" + index + " expected " + expected + " but was tag " + actual.tag());
    }
}
