package dev.aegis.util;

import dev.aegis.classfile.*;
import java.util.*;

public final class BytecodePrinter {
    private BytecodePrinter() {}

    public static String print(byte[] bytes) {
        ClassFile cf = ClassFileParser.parse(bytes);
        StringBuilder out = new StringBuilder();
        out.append("// Aegis native classfile decoder\n");
        out.append("// classfile ").append(cf.majorVersion()).append('.').append(cf.minorVersion())
                .append(" (Java ").append(cf.javaVersion()).append(")\n");
        out.append("class ").append(cf.thisClass()).append(" extends ").append(cf.superClass()).append("\n\n");
        for (MemberInfo field : cf.fields()) {
            out.append("FIELD ").append(field.name()).append(' ').append(field.descriptor()).append(" flags=0x")
                    .append(Integer.toHexString(field.accessFlags())).append('\n');
        }
        if (!cf.fields().isEmpty()) out.append('\n');
        for (MemberInfo method : cf.methods()) {
            out.append("METHOD ").append(method.name()).append(method.descriptor()).append(" flags=0x")
                    .append(Integer.toHexString(method.accessFlags())).append('\n');
            AttributeInfo a = method.attribute("Code");
            if (a == null) { out.append("  <no Code attribute>\n\n"); continue; }
            try {
                CodeAttribute code = CodeAttribute.parse(a, cf.constantPool());
                out.append("  max_stack=").append(code.maxStack()).append(" max_locals=").append(code.maxLocals()).append('\n');
                Set<Integer> labels = new TreeSet<>();
                List<Instruction> insns = BytecodeDecoder.decode(code.code());
                for (Instruction insn : insns) for (int t : insn.branchTargets()) labels.add(t);
                for (CodeAttribute.ExceptionHandler h : code.exceptionTable()) { labels.add(h.startPc()); labels.add(h.endPc()); labels.add(h.handlerPc()); }
                for (Instruction insn : insns) {
                    if (labels.contains(insn.offset())) out.append(String.format("L%04x:%n", insn.offset()));
                    out.append(String.format("  %04x  %-18s", insn.offset(), insn.mnemonic()));
                    out.append(formatOperands(insn, cf.constantPool()));
                    out.append('\n');
                }
                if (!code.exceptionTable().isEmpty()) {
                    out.append("  exception table:\n");
                    for (CodeAttribute.ExceptionHandler h : code.exceptionTable()) {
                        out.append(String.format("    L%04x..L%04x -> L%04x  %s%n", h.startPc(), h.endPc(), h.handlerPc(), h.catchType() == null ? "<any>" : h.catchType()));
                    }
                }
            } catch (RuntimeException ex) {
                out.append("  <decoder error: ").append(ex.getMessage()).append(">\n");
            }
            out.append('\n');
        }
        return out.toString();
    }

    private static String formatOperands(Instruction i, ConstantPool cp) {
        int op = i.opcode();
        try {
            if (op == 18) return "#" + i.u1(0) + " // " + formatConstant(cp.constant(i.u1(0)));
            if (op == 19 || op == 20) return "#" + i.u2(0) + " // " + formatConstant(cp.constant(i.u2(0)));
            if ((op >= 178 && op <= 184) || op == 185) {
                int idx = i.u2(0); ConstantPool.MemberRef r = cp.memberRef(idx);
                return "#" + idx + " // " + r.owner() + "." + r.name() + r.descriptor();
            }
            if (op == 186) {
                int idx = i.u2(0); ConstantPool.DynamicRef r = cp.dynamicRef(idx);
                return "#" + idx + " // invokedynamic " + r.name() + r.descriptor() + " bootstrap=" + r.bootstrapMethodIndex();
            }
            if (op == 187 || op == 189 || op == 192 || op == 193) {
                int idx = i.u2(0); return "#" + idx + " // " + cp.className(idx);
            }
            if (i.branchTargets().length == 1) return String.format("L%04x", i.branchTargets()[0]);
            if (op == 16) return Integer.toString(i.s1(0));
            if (op == 17) return Short.toString(i.s2(0));
            if ((op >= 21 && op <= 25) || (op >= 54 && op <= 58) || op == 169) return Integer.toString(i.u1(0));
            if (op == 132) return i.u1(0) + " " + i.s1(1);
            if (op == 188) return Integer.toString(i.u1(0));
            if (op == 197) return "#" + i.u2(0) + " dims=" + i.u1(2) + " // " + cp.className(i.u2(0));
            if (op == 170 || op == 171) return "targets=" + Arrays.toString(i.branchTargets());
            if (i.operands().length > 0) return Arrays.toString(i.operands());
            return "";
        } catch (RuntimeException ex) {
            return " " + Arrays.toString(i.operands()) + " // unresolved: " + ex.getMessage();
        }
    }

    private static String formatConstant(Object o) {
        if (o instanceof String s) return JavaNames.escapeString(s);
        if (o instanceof ConstantPool.ClassLiteral c) return c.internalName().replace('/', '.') + ".class";
        return String.valueOf(o);
    }
}
