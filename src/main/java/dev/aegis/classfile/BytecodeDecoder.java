package dev.aegis.classfile;

import java.util.*;

public final class BytecodeDecoder {
    private BytecodeDecoder() {}

    public static List<Instruction> decode(byte[] code) {
        ArrayList<Instruction> out = new ArrayList<>();
        int p = 0;
        while (p < code.length) {
            int start = p;
            int op = code[p++] & 0xFF;
            String name = OpcodeTable.name(op);
            byte[] operands;
            int[] targets = new int[0];

            if (op == 170) { // tableswitch
                int pad = (4 - (p & 3)) & 3;
                ensure(code, p, pad + 12, start);
                p += pad;
                int def = s4(code, p); p += 4;
                int low = s4(code, p); p += 4;
                int high = s4(code, p); p += 4;
                long count = (long) high - low + 1L;
                if (count < 0 || count > 1_000_000L) throw new IllegalArgumentException("Bad tableswitch at " + start);
                ensure(code, p, (int) count * 4, start);
                targets = new int[(int) count + 1];
                targets[0] = start + def;
                for (int i = 0; i < count; i++) { targets[i + 1] = start + s4(code, p); p += 4; }
                operands = Arrays.copyOfRange(code, start + 1, p);
            } else if (op == 171) { // lookupswitch
                int pad = (4 - (p & 3)) & 3;
                ensure(code, p, pad + 8, start);
                p += pad;
                int def = s4(code, p); p += 4;
                int pairs = s4(code, p); p += 4;
                if (pairs < 0 || pairs > 1_000_000) throw new IllegalArgumentException("Bad lookupswitch at " + start);
                ensure(code, p, pairs * 8, start);
                targets = new int[pairs + 1];
                targets[0] = start + def;
                for (int i = 0; i < pairs; i++) {
                    p += 4; // match
                    targets[i + 1] = start + s4(code, p); p += 4;
                }
                operands = Arrays.copyOfRange(code, start + 1, p);
            } else if (op == 196) { // wide
                ensure(code, p, 1, start);
                int widened = code[p] & 0xFF;
                int extra = widened == 132 ? 5 : 3; // opcode + u2 index [+ s2 const]
                ensure(code, p, extra, start);
                p += extra;
                operands = Arrays.copyOfRange(code, start + 1, p);
            } else {
                int operandCount = fixedOperands(op);
                ensure(code, p, operandCount, start);
                operands = Arrays.copyOfRange(code, p, p + operandCount);
                p += operandCount;
                if ((op >= 153 && op <= 168) || op == 198 || op == 199) {
                    targets = new int[]{start + (short)(((operands[0] & 0xFF) << 8) | (operands[1] & 0xFF))};
                } else if (op == 200 || op == 201) {
                    int delta = ((operands[0] & 0xFF) << 24) | ((operands[1] & 0xFF) << 16)
                            | ((operands[2] & 0xFF) << 8) | (operands[3] & 0xFF);
                    targets = new int[]{start + delta};
                }
            }
            out.add(new Instruction(start, op, name, operands, p - start, targets));
        }
        return List.copyOf(out);
    }

    private static int fixedOperands(int op) {
        return switch (op) {
            case 16, 18, 21,22,23,24,25,54,55,56,57,58,169,188 -> 1;
            case 17,19,20,132,
                    153,154,155,156,157,158,159,160,161,162,163,164,165,166,167,168,
                    178,179,180,181,182,183,184,187,189,192,193,198,199 -> 2;
            case 197 -> 3;
            case 185,186,200,201 -> 4;
            default -> 0;
        };
    }

    private static int s4(byte[] code, int p) {
        return ((code[p] & 0xFF) << 24) | ((code[p+1] & 0xFF) << 16) | ((code[p+2] & 0xFF) << 8) | (code[p+3] & 0xFF);
    }

    private static void ensure(byte[] code, int p, int n, int start) {
        if (p < 0 || n < 0 || p + n > code.length) throw new IllegalArgumentException("Truncated instruction at bytecode offset " + start);
    }
}
