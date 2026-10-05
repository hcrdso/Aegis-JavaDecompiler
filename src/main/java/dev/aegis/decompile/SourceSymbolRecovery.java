package dev.aegis.decompile;

import dev.aegis.util.JavaNames;
import java.util.*;
import java.util.regex.*;

public final class SourceSymbolRecovery {
    public record MethodSymbols(String name, List<String> parameterNames, int declarationLine) {}
    public record FieldSymbol(String name, String type, int declarationLine) {}

    private static final Set<String> CONTROL = Set.of(
            "if","for","while","switch","catch","synchronized","return","throw","new","assert","do","try");

    public static MethodSymbols method(String source, int minLine, int maxLine, int parameterCount, boolean constructor, String ownerSimpleName) {
        if (source == null || source.isBlank() || minLine <= 0) return null;
        String[] lines = source.split("\\R", -1);
        int from = Math.max(1, minLine - 16);
        int to = Math.min(lines.length, Math.max(minLine + 3, Math.min(maxLine + 1, minLine + 8)));
        MethodSymbols best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (int start = from; start <= to; start++) {
            StringBuilder joined = new StringBuilder();
            for (int end = start; end <= Math.min(to, start + 10); end++) {
                String line = stripStringsAndComments(lines[end - 1]);
                if (!line.isBlank()) joined.append(' ').append(line.trim());
                String text = joined.toString().trim();
                if (!text.contains("(")) continue;
                if (!(text.contains("{") || text.endsWith(";"))) continue;
                MethodSymbols found = parseDeclaration(text, parameterCount, constructor, ownerSimpleName, start);
                if (found != null) {
                    int distance = Math.abs(minLine - end);
                    if (distance < bestDistance) {
                        best = found;
                        bestDistance = distance;
                    }
                    break;
                }
                if (text.contains(";") && !text.contains("->")) break;
            }
        }
        return best;
    }


    public static List<FieldSymbol> fields(String source) {
        if (source == null || source.isBlank()) return List.of();
        String[] lines = source.split("\\R", -1);
        ArrayList<FieldSymbol> out = new ArrayList<>();
        int depth = 0;
        boolean blockComment = false;
        StringBuilder pending = new StringBuilder();
        int pendingLine = -1;
        for (int line = 1; line <= lines.length; line++) {
            String cleaned = stripLine(lines[line - 1], blockComment);
            blockComment = blockCommentState(lines[line - 1], blockComment);
            int before = depth;
            depth += braceDelta(cleaned);
            if (before == 1 && !cleaned.isBlank()) {
                if (pendingLine < 0) pendingLine = line;
                if (pending.length() > 0) pending.append(' ');
                pending.append(cleaned.trim());
                if (cleaned.indexOf(';') >= 0) {
                    FieldSymbol symbol = parseField(pending.toString(), pendingLine);
                    if (symbol != null) out.add(symbol);
                    pending.setLength(0);
                    pendingLine = -1;
                } else if (cleaned.indexOf('{') >= 0 || cleaned.indexOf('(') >= 0) {
                    pending.setLength(0);
                    pendingLine = -1;
                }
            } else if (before != 1) {
                pending.setLength(0);
                pendingLine = -1;
            }
        }
        return List.copyOf(out);
    }

    private static FieldSymbol parseField(String declaration, int line) {
        String x = declaration;
        int semi = x.indexOf(';');
        if (semi >= 0) x = x.substring(0, semi);
        if (x.indexOf('(') >= 0 || x.contains(" class ") || x.contains(" interface ") || x.contains(" record ") || x.contains(" enum ")) return null;
        x = x.replaceAll("@[A-Za-z_$][\\w$]*(?:\\s*\\([^)]*\\))?", " ")
                .replaceAll("\\b(public|protected|private|static|final|transient|volatile)\\b", " ")
                .trim();
        int eq = x.indexOf('=');
        String left = eq >= 0 ? x.substring(0, eq).trim() : x;
        Matcher m = Pattern.compile("(.+?)\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*$").matcher(left);
        if (!m.matches()) return null;
        String type = m.group(1).trim();
        String name = m.group(2);
        if (!JavaNames.isValidIdentifier(name) || JavaNames.isKeyword(name)) return null;
        return new FieldSymbol(name, type, line);
    }

    private static String stripLine(String line, boolean inBlock) {
        StringBuilder out = new StringBuilder(line.length());
        boolean string = false, chr = false, esc = false, block = inBlock;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            char n = i + 1 < line.length() ? line.charAt(i + 1) : 0;
            if (block) {
                if (c == '*' && n == '/') { block = false; i++; out.append(' '); }
                continue;
            }
            if (!string && !chr && c == '/' && n == '*') { block = true; i++; out.append(' '); continue; }
            if (!string && !chr && c == '/' && n == '/') break;
            if (esc) { esc = false; out.append(' '); continue; }
            if ((string || chr) && c == '\\') { esc = true; out.append(' '); continue; }
            if (!chr && c == '"') { string = !string; out.append(' '); continue; }
            if (!string && c == '\'') { chr = !chr; out.append(' '); continue; }
            out.append(string || chr ? ' ' : c);
        }
        return out.toString();
    }

    private static boolean blockCommentState(String line, boolean initial) {
        boolean block = initial, string = false, chr = false, esc = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            char n = i + 1 < line.length() ? line.charAt(i + 1) : 0;
            if (block) { if (c == '*' && n == '/') { block = false; i++; } continue; }
            if (!string && !chr && c == '/' && n == '/') break;
            if (!string && !chr && c == '/' && n == '*') { block = true; i++; continue; }
            if (esc) { esc = false; continue; }
            if ((string || chr) && c == '\\') { esc = true; continue; }
            if (!chr && c == '"') string = !string;
            else if (!string && c == '\'') chr = !chr;
        }
        return block;
    }

    private static int braceDelta(String text) {
        int delta = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') delta++;
            else if (c == '}') delta--;
        }
        return delta;
    }

    public static Map<Integer,List<String>> localNamesByLine(String source, int minLine, int maxLine) {
        if (source == null || source.isBlank() || minLine <= 0 || maxLine < minLine) return Map.of();
        String[] lines = source.split("\\R", -1);
        int from = Math.max(1, minLine);
        int to = Math.min(lines.length, maxLine);
        Pattern declaration = Pattern.compile("(?:\\bfinal\\s+)?(?:[A-Za-z_$][\\w$]*\\.)*[A-Za-z_$][\\w$]*(?:\\s*<[^;=(){}]+>)?(?:\\s*\\[\\s*\\])*(?:\\.\\.\\.)?\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*(?==|;|:|,)");
        LinkedHashMap<Integer,List<String>> out = new LinkedHashMap<>();
        for (int line = from; line <= to; line++) {
            String text = stripStringsAndComments(lines[line - 1]);
            Matcher m = declaration.matcher(text);
            ArrayList<String> names = new ArrayList<>();
            while (m.find()) {
                String name = m.group(1);
                if (JavaNames.isValidIdentifier(name) && !JavaNames.isKeyword(name) && !CONTROL.contains(name)) names.add(name);
            }
            if (!names.isEmpty()) out.put(line, List.copyOf(names));
        }
        return Collections.unmodifiableMap(out);
    }

    private static MethodSymbols parseDeclaration(String text, int parameterCount, boolean constructor, String ownerSimpleName, int line) {
        int open = text.indexOf('(');
        if (open < 0) return null;
        int close = matchingParen(text, open);
        if (close < 0) return null;
        String before = text.substring(0, open).trim();
        Matcher names = Pattern.compile("([A-Za-z_$][A-Za-z0-9_$]*)\\s*$").matcher(before);
        if (!names.find()) return null;
        String name = names.group(1);
        if (CONTROL.contains(name)) return null;
        if (constructor && !name.equals(ownerSimpleName)) return null;
        String params = text.substring(open + 1, close);
        List<String> parts = splitParameters(params);
        if (parts.size() != parameterCount) return null;
        ArrayList<String> recovered = new ArrayList<>(parts.size());
        for (int i = 0; i < parts.size(); i++) {
            String p = parameterName(parts.get(i));
            if (p == null) return null;
            recovered.add(p);
        }
        return new MethodSymbols(name, List.copyOf(recovered), line);
    }

    private static List<String> splitParameters(String params) {
        if (params.isBlank()) return List.of();
        ArrayList<String> out = new ArrayList<>();
        int angle = 0, paren = 0, bracket = 0, brace = 0;
        boolean string = false, chr = false, esc = false;
        int start = 0;
        for (int i = 0; i < params.length(); i++) {
            char c = params.charAt(i);
            if (esc) { esc = false; continue; }
            if ((string || chr) && c == '\\') { esc = true; continue; }
            if (!chr && c == '"') { string = !string; continue; }
            if (!string && c == '\'') { chr = !chr; continue; }
            if (string || chr) continue;
            if (c == '<') angle++;
            else if (c == '>') angle = Math.max(0, angle - 1);
            else if (c == '(') paren++;
            else if (c == ')') paren = Math.max(0, paren - 1);
            else if (c == '[') bracket++;
            else if (c == ']') bracket = Math.max(0, bracket - 1);
            else if (c == '{') brace++;
            else if (c == '}') brace = Math.max(0, brace - 1);
            else if (c == ',' && angle == 0 && paren == 0 && bracket == 0 && brace == 0) {
                out.add(params.substring(start, i).trim());
                start = i + 1;
            }
        }
        out.add(params.substring(start).trim());
        return out;
    }

    private static String parameterName(String raw) {
        String x = raw.replaceAll("@[A-Za-z_$][\\w$]*(?:\\s*\\([^)]*\\))?", " ")
                .replaceAll("\\bfinal\\b", " ")
                .replace("...", " ")
                .trim();
        Matcher m = Pattern.compile("([A-Za-z_$][A-Za-z0-9_$]*)\\s*(?:\\[\\s*\\])?\\s*$").matcher(x);
        if (!m.find()) return null;
        String name = m.group(1);
        return JavaNames.isValidIdentifier(name) && !JavaNames.isKeyword(name) ? name : null;
    }

    private static int matchingParen(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return i;
        }
        return -1;
    }

    private static String stripStringsAndComments(String line) {
        StringBuilder out = new StringBuilder(line.length());
        boolean string = false, chr = false, esc = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (!string && !chr && i + 1 < line.length() && c == '/' && line.charAt(i + 1) == '/') break;
            if (esc) { esc = false; out.append(' '); continue; }
            if ((string || chr) && c == '\\') { esc = true; out.append(' '); continue; }
            if (!chr && c == '"') { string = !string; out.append(' '); continue; }
            if (!string && c == '\'') { chr = !chr; out.append(' '); continue; }
            out.append(string || chr ? ' ' : c);
        }
        return out.toString();
    }
}
