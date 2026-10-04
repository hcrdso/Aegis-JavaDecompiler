package dev.aegis.analysis;

import dev.aegis.classfile.*;
import dev.aegis.util.JavaNames;
import dev.aegis.workspace.*;
import java.util.*;

public final class AnalyzerService {
    public AnalysisReport analyze(Workspace workspace) {
        ObfuscationFingerprint.Result fingerprint = new ObfuscationFingerprint().inspect(workspace);
        List<AnalysisReport.ClassFinding> findings = new ArrayList<>();
        int suspiciousIdentifiers = 0, indy = 0, handlers = 0, jumps = 0, suspiciousClasses = 0;
        for (ClassUnit unit : workspace.classes()) {
            int score = 0;
            ArrayList<String> reasons = new ArrayList<>();
            try {
                ClassFile cf = ClassFileParser.parse(unit.bytes());
                String simple = cf.thisClass().substring(cf.thisClass().lastIndexOf('/') + 1);
                if (JavaNames.isLikelyObfuscated(simple)) { suspiciousIdentifiers++; score += 25; reasons.add("class name"); }
                int memberObf = 0, localJumps = 0, localInsns = 0, localHandlers = 0, localIndy = 0;
                for (MemberInfo f : cf.fields()) if (JavaNames.isLikelyObfuscated(f.name())) memberObf++;
                for (MemberInfo m : cf.methods()) {
                    if (!m.name().startsWith("<") && JavaNames.isLikelyObfuscated(m.name())) memberObf++;
                    AttributeInfo ca = m.attribute("Code");
                    if (ca == null) continue;
                    try {
                        CodeAttribute c = CodeAttribute.parse(ca, cf.constantPool());
                        localHandlers += c.exceptionTable().size();
                        List<Instruction> list = BytecodeDecoder.decode(c.code());
                        localInsns += list.size();
                        for (Instruction i : list) {
                            if (i.isBranch()) localJumps++;
                            if (i.opcode() == 186) localIndy++;
                        }
                    } catch (RuntimeException ex) {
                        score += 8; reasons.add("malformed/hostile Code attribute");
                    }
                }
                suspiciousIdentifiers += memberObf;
                jumps += localJumps; handlers += localHandlers; indy += localIndy;
                int members = Math.max(1, cf.fields().size() + cf.methods().size());
                if (memberObf > 0) { score += Math.min(40, memberObf * 40 / members); reasons.add(memberObf + " suspicious member names"); }
                if (localInsns > 30 && localJumps * 5 > localInsns) { score += 15; reasons.add("jump-heavy control flow"); }
                if (localHandlers > Math.max(4, cf.methods().size() * 2)) { score += 10; reasons.add("dense exception flow"); }
                int weird = countWeirdUtf8(cf.constantPool());
                if (weird >= 3) { score += Math.min(10, weird / 2); reasons.add("encoded/high-entropy constants"); }
                if (localIndy > Math.max(8, cf.methods().size() * 2)) { score += 5; reasons.add("heavy invokedynamic"); }
            } catch (RuntimeException ex) {
                score = 100; reasons.add("native parser failure: " + safe(ex));
            }
            score = Math.min(100, score);
            if (score >= 35) suspiciousClasses++;
            findings.add(new AnalysisReport.ClassFinding(unit.internalName(), score, List.copyOf(reasons)));
        }
        findings.sort(Comparator.comparingInt(AnalysisReport.ClassFinding::score).reversed().thenComparing(AnalysisReport.ClassFinding::internalName));
        int total = workspace.classes().size();
        int global = total == 0 ? 0 : Math.min(100, (suspiciousClasses * 55 / total)
                + Math.min(35, suspiciousIdentifiers * 35 / Math.max(4, total * 6))
                + Math.min(10, jumps / Math.max(1, total * 10)));
        ArrayList<String> diagnostics = new ArrayList<>(workspace.diagnostics());
        if (fingerprint.retroGuardStyleScore() > 0) {
            diagnostics.add("RetroGuard/yGuard-style fingerprint: " + fingerprint.retroGuardStyleScore() + "/100");
            diagnostics.addAll(fingerprint.notes());
        }
        return new AnalysisReport(global, total, suspiciousClasses, suspiciousIdentifiers, indy, handlers, jumps,
                List.copyOf(findings), List.copyOf(diagnostics));
    }

    private static int countWeirdUtf8(ConstantPool cp) {
        int count = 0;
        for (String s : cp.utf8Strings()) if (looksEncoded(s)) count++;
        return count;
    }

    private static boolean looksEncoded(String s) {
        if (s.length() < 32) return false;
        int printable = 0, symbols = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 32 && c < 127) printable++;
            if (!Character.isLetterOrDigit(c) && !Character.isWhitespace(c)) symbols++;
        }
        return printable < s.length() * 0.75 || symbols > s.length() / 3;
    }

    private static String safe(Throwable t) {
        String m = t.getMessage(); if (m == null) return t.getClass().getSimpleName();
        return m.length() > 120 ? m.substring(0, 120) + "…" : m;
    }
}
