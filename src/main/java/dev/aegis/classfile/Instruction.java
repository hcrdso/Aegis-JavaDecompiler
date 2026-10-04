package dev.aegis.classfile;

import java.util.Arrays;

public record Instruction(int offset, int opcode, String mnemonic, byte[] operands, int length, int[] branchTargets) {
    public int u1(int i) { return operands[i] & 0xFF; }
    public int s1(int i) { return operands[i]; }
    public int u2(int i) { return ((operands[i] & 0xFF) << 8) | (operands[i + 1] & 0xFF); }
    public short s2(int i) { return (short) u2(i); }
    public int s4(int i) {
        return ((operands[i] & 0xFF) << 24) | ((operands[i+1] & 0xFF) << 16)
                | ((operands[i+2] & 0xFF) << 8) | (operands[i+3] & 0xFF);
    }
    public boolean isBranch() { return branchTargets.length > 0; }
    @Override public String toString() { return offset + ": " + mnemonic + " " + Arrays.toString(operands); }
}
