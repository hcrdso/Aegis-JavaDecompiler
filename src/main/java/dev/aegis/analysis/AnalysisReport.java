package dev.aegis.analysis;

import java.util.*;

public record AnalysisReport(int score, int classCount, int suspiciousClasses, int suspiciousIdentifiers,
                             int invokeDynamicCount, int exceptionHandlerCount, int jumpCount,
                             List<ClassFinding> findings, List<String> diagnostics) {
    public record ClassFinding(String internalName, int score, List<String> reasons) {}

    public String format() {
        StringBuilder b = new StringBuilder();
        b.append("AEGIS NATIVE ANALYSIS\n");
        b.append("=====================\n");
        b.append("Obfuscation score: ").append(score).append("/100\n");
        b.append("Classes: ").append(classCount).append("\n");
        b.append("Suspicious classes: ").append(suspiciousClasses).append("\n");
        b.append("Suspicious identifiers: ").append(suspiciousIdentifiers).append("\n");
        b.append("invokedynamic instructions: ").append(invokeDynamicCount).append("\n");
        b.append("Exception handlers: ").append(exceptionHandlerCount).append("\n");
        b.append("Control-flow jumps: ").append(jumpCount).append("\n\n");
        b.append("Highest-risk classes\n--------------------\n");
        findings.stream().limit(100).forEach(f -> b.append(String.format("%3d  %s", f.score(), f.internalName()))
                .append(f.reasons().isEmpty() ? "" : " — " + String.join(", ", f.reasons())).append('\n'));
        if (!diagnostics.isEmpty()) {
            b.append("\nParser/archive diagnostics\n--------------------------\n");
            diagnostics.forEach(x -> b.append(x).append('\n'));
        }
        return b.toString();
    }
}
