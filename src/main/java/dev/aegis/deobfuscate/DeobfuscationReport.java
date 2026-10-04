package dev.aegis.deobfuscate;

import java.util.*;

public record DeobfuscationReport(
        int unreachableInstructions,
        int opaquePredicates,
        int redundantJumps,
        int constantFolds,
        int deadStores,
        int suspiciousNops,
        List<String> notes) {
    public int totalSimplifications() {
        return unreachableInstructions + opaquePredicates + redundantJumps + constantFolds + deadStores + suspiciousNops;
    }
    public String compact() {
        return "dead=" + unreachableInstructions + ", opaque=" + opaquePredicates + ", jumps=" + redundantJumps
                + ", folds=" + constantFolds + ", stores=" + deadStores + ", nops=" + suspiciousNops;
    }
}
