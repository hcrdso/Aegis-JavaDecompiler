package dev.aegis.util;

import java.util.Set;

public final class JavaNames {
    private static final Set<String> KEYWORDS = Set.of(
            "abstract","assert","boolean","break","byte","case","catch","char","class","const","continue","default","do","double","else","enum","extends","final","finally","float","for","goto","if","implements","import","instanceof","int","interface","long","native","new","package","private","protected","public","return","short","static","strictfp","super","switch","synchronized","this","throw","throws","transient","try","void","volatile","while","true","false","null","record","sealed","permits","non-sealed","var","yield");
    private JavaNames() {}

    public static boolean isKeyword(String s) { return s != null && KEYWORDS.contains(s); }

    public static boolean isValidIdentifier(String s) {
        if (s == null || s.isEmpty() || KEYWORDS.contains(s)) return false;
        if (!Character.isJavaIdentifierStart(s.codePointAt(0))) return false;
        for (int i = Character.charCount(s.codePointAt(0)); i < s.length();) {
            int cp = s.codePointAt(i);
            if (!Character.isJavaIdentifierPart(cp)) return false;
            i += Character.charCount(cp);
        }
        return true;
    }

    public static String sanitize(String s, String fallback) {
        if (isValidIdentifier(s)) return s;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length();) {
            int cp = s.codePointAt(i);
            boolean ok = b.isEmpty() ? Character.isJavaIdentifierStart(cp) : Character.isJavaIdentifierPart(cp);
            b.appendCodePoint(ok ? cp : '_');
            i += Character.charCount(cp);
        }
        String out = b.toString();
        if (!isValidIdentifier(out)) return fallback;
        return out;
    }

    public static boolean isLikelyObfuscated(String s) {
        if (s == null || s.isEmpty()) return true;
        if (!isValidIdentifier(s)) return true;
        if (s.length() <= 2 && !Set.of("id","x","y","z","i","j","k","ok","ui").contains(s)) return true;
        if (s.length() >= 3 && s.length() <= 6 && allSameCharacter(s)) return true;
        if (s.length() >= 3 && s.length() <= 5 && monotonicAsciiLetters(s)) return true;
        int letters = 0, weird = 0, transitions = 0;
        char lastKind = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            char kind = Character.isLowerCase(c) ? 'l' : Character.isUpperCase(c) ? 'u' : Character.isDigit(c) ? 'd' : 'o';
            if (Character.isLetter(c)) letters++; else if (c != '_' && !Character.isDigit(c)) weird++;
            if (lastKind != 0 && kind != lastKind) transitions++;
            lastKind = kind;
        }
        if (weird > 0) return true;
        if (s.length() >= 12 && letters > 0 && transitions > s.length() / 2) return true;
        return s.matches("[Il1O0]{3,}") || s.matches("[a-zA-Z]{1,2}\\d{3,}");
    }

    private static boolean allSameCharacter(String s) {
        int first=s.codePointAt(0);
        for(int i=Character.charCount(first);i<s.length();){int cp=s.codePointAt(i);if(cp!=first)return false;i+=Character.charCount(cp);}
        return true;
    }

    private static boolean monotonicAsciiLetters(String s) {
        for(int i=0;i<s.length();i++)if(!Character.isLetter(s.charAt(i)))return false;
        int delta=s.charAt(1)-s.charAt(0);
        if(delta!=1&&delta!=-1)return false;
        for(int i=2;i<s.length();i++)if(s.charAt(i)-s.charAt(i-1)!=delta)return false;
        return true;
    }

    public static String escapeString(String s) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 32 || c > 126) out.append(String.format("\\u%04x", (int)c));
                    else out.append(c);
                }
            }
        }
        return out.append('\"').toString();
    }
}
