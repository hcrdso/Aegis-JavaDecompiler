package dev.aegis.classfile;

import java.util.*;

public record CodeAttribute(int maxStack, int maxLocals, byte[] code,
                            List<ExceptionHandler> exceptionTable,
                            List<AttributeInfo> attributes) {
    public record ExceptionHandler(int startPc, int endPc, int handlerPc, String catchType) {}

    public static CodeAttribute parse(AttributeInfo attribute, ConstantPool cp) {
        ByteReader in = attribute.reader();
        int maxStack = in.u2();
        int maxLocals = in.u2();
        long codeLengthLong = in.u4();
        if (codeLengthLong > 65535L) throw new IllegalArgumentException("Invalid Code length: " + codeLengthLong);
        byte[] code = in.bytes((int) codeLengthLong);
        int exCount = in.u2();
        ArrayList<ExceptionHandler> handlers = new ArrayList<>(exCount);
        for (int i = 0; i < exCount; i++) {
            int start = in.u2(), end = in.u2(), handler = in.u2(), catchIndex = in.u2();
            String catchType = catchIndex == 0 ? null : cp.className(catchIndex);
            handlers.add(new ExceptionHandler(start, end, handler, catchType));
        }
        int attrCount = in.u2();
        ArrayList<AttributeInfo> nested = new ArrayList<>(attrCount);
        for (int i = 0; i < attrCount; i++) nested.add(ClassFileParser.readAttribute(in, cp));
        return new CodeAttribute(maxStack, maxLocals, code, List.copyOf(handlers), List.copyOf(nested));
    }

    public AttributeInfo attribute(String name) {
        for (AttributeInfo a : attributes) if (a.name().equals(name)) return a;
        return null;
    }
}
