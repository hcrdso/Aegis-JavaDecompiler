package dev.aegis.classfile;

public record AttributeInfo(String name, byte[] data) {
    public ByteReader reader() { return new ByteReader(data); }
}
