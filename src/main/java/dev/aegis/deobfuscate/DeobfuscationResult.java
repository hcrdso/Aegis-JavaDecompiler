package dev.aegis.deobfuscate;

import dev.aegis.classfile.Instruction;
import java.util.*;

/** Analysis-only rewrite plan: original bytes are never executed or mutated. */
public record DeobfuscationResult(
        List<Instruction> instructions,
        Set<Integer> unreachableOffsets,
        Map<Integer, Boolean> forcedConditionalBranches,
        Map<Integer, Integer> forcedSwitchTargets,
        Map<Integer, Integer> threadedGotoTargets,
        Set<Integer> deadStoreOffsets,
        DeobfuscationReport report) {
    public boolean isUnreachable(int offset) { return unreachableOffsets.contains(offset); }
    public Boolean forcedBranch(int offset) { return forcedConditionalBranches.get(offset); }
    public Integer forcedSwitchTarget(int offset) { return forcedSwitchTargets.get(offset); }
    public int threadedTarget(int offset, int fallback) { return threadedGotoTargets.getOrDefault(offset, fallback); }
}
