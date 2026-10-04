package dev.aegis.classfile;

import java.io.*;
import java.nio.charset.StandardCharsets;

public final class ByteReader {
    private final byte[] data;
    private int pos;

    public ByteReader(byte[] data) {
        this(data, 0);
    }

    public ByteReader(byte[] data, int pos) {
        this.data = data;
        this.pos = pos;
    }

    public int position() { return pos; }
    public int remaining() { return data.length - pos; }
    public void position(int newPos) {
        if (newPos < 0 || newPos > data.length) throw new IllegalArgumentException("position");
        pos = newPos;
    }

    public int u1() {
        require(1);
        return data[pos++] & 0xFF;
    }

    public int s1() {
        return (byte) u1();
    }

    public int u2() {
        require(2);
        int v = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
        pos += 2;
        return v;
    }

    public short s2() {
        return (short) u2();
    }

    public long u4() {
        require(4);
        long v = ((long)(data[pos] & 0xFF) << 24)
                | ((long)(data[pos + 1] & 0xFF) << 16)
                | ((long)(data[pos + 2] & 0xFF) << 8)
                | (long)(data[pos + 3] & 0xFF);
        pos += 4;
        return v;
    }

    public int s4() {
        return (int) u4();
    }

    public byte[] bytes(int len) {
        if (len < 0) throw new IllegalArgumentException("negative length");
        require(len);
        byte[] out = java.util.Arrays.copyOfRange(data, pos, pos + len);
        pos += len;
        return out;
    }

    public void skip(int len) {
        require(len);
        pos += len;
    }

    public String modifiedUtf8() throws IOException {
        int len = u2();
        byte[] payload = bytes(len);
        byte[] framed = new byte[len + 2];
        framed[0] = (byte) ((len >>> 8) & 0xFF);
        framed[1] = (byte) (len & 0xFF);
        System.arraycopy(payload, 0, framed, 2, len);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(framed))) {
            return in.readUTF();
        }
    }

    public String ascii(int len) {
        return new String(bytes(len), StandardCharsets.ISO_8859_1);
    }

    private void require(int len) {
        if (pos + len > data.length) {
            throw new IllegalArgumentException("Unexpected end of classfile at offset " + pos + " (need " + len + " bytes)");
        }
    }
}
