package dev.aegis.decompile;

import java.util.*;
import java.util.regex.*;

final class SourcePostProcessor {
    private static final Pattern DECL = Pattern.compile("^(\\s*)([\\w.$<>?, \\[\\]]+)\\s+([A-Za-z_$][\\w$]*);$");
    private static final Pattern ITERATOR_ASSIGN = Pattern.compile("^(\\s*)([A-Za-z_$][\\w$]*)\\s*=\\s*(.+)\\.iterator\\(\\);$");
    private static final Pattern HAS_NEXT = Pattern.compile("^(\\s*)while\\s*\\((.*?)([A-Za-z_$][\\w$]*)\\.hasNext\\(\\)(.*?)\\)\\s*\\{$");
    private static final Pattern CAST_NEXT = Pattern.compile("^(\\s*)([A-Za-z_$][\\w$]*)\\s*=\\s*\\(\\(([^)]+)\\)\\s*([A-Za-z_$][\\w$]*)\\.next\\(\\)\\);$");
    private static final Pattern NEXT = Pattern.compile("^(\\s*)([A-Za-z_$][\\w$]*)\\s*=\\s*([A-Za-z_$][\\w$]*)\\.next\\(\\);$");
    private static final Pattern ASSIGN = Pattern.compile("^(\\s*)([A-Za-z_$][\\w$]*)\\s*=\\s*(.+);$");
    private static final Pattern WHILE = Pattern.compile("^(\\s*)while\\s*\\((.+)\\)\\s*\\{$");
    private static final Pattern STEP = Pattern.compile("^(\\s*)([A-Za-z_$][\\w$]*)\\s*([+\\-])=\\s*([0-9]+);$");
    private static final Pattern IF = Pattern.compile("^(\\s*)if\\s*\\((.+)\\)\\s*\\{$");
    private static final Pattern RETURN_BOOL = Pattern.compile("^(\\s*)return\\s+(true|false);$");

    static List<String> process(List<String> source, String returnType) {
        ArrayList<String> out = new ArrayList<>(source);
        for (int pass = 0; pass < 6; pass++) {
            String before = String.join("\n", out);
            recoverDiamondOperators(out);
            foldDeclarationAssignments(out);
            recoverEnhancedFor(out);
            recoverCountedFor(out);
            simplifySteps(out);
            mergeNestedIfs(out);
            simplifyBooleanReturns(out);
            if (before.equals(String.join("\n", out))) break;
        }
        if ("void".equals(returnType)) removeTrailingReturn(out);
        return List.copyOf(out);
    }


    private static void foldDeclarationAssignments(ArrayList<String> out) {
        for (int i = 0; i + 1 < out.size(); i++) {
            Matcher decl = DECL.matcher(out.get(i));
            if (!decl.matches()) continue;
            int next = nextContent(out, i + 1);
            if (next < 0) continue;
            Matcher assign = ASSIGN.matcher(out.get(next));
            if (!assign.matches()) continue;
            if (!decl.group(1).equals(assign.group(1)) || !decl.group(3).equals(assign.group(2))) continue;
            String expr = assign.group(3).trim();
            if (containsWord(expr, decl.group(3))) continue;
            out.set(i, decl.group(1) + decl.group(2).trim() + " " + decl.group(3) + " = " + expr + ";");
            out.remove(next);
            if (i + 1 < out.size() && out.get(i + 1).isBlank()) out.remove(i + 1);
        }
    }

    private static void mergeNestedIfs(ArrayList<String> out) {
        for (int i = 0; i < out.size(); i++) {
            Matcher outer = IF.matcher(out.get(i));
            if (!outer.matches()) continue;
            int innerIndex = nextContent(out, i + 1);
            if (innerIndex < 0) continue;
            Matcher inner = IF.matcher(out.get(innerIndex));
            if (!inner.matches()) continue;
            if (!inner.group(1).startsWith(outer.group(1) + "    ")) continue;
            int innerClose = matchingBrace(out, innerIndex);
            int outerClose = matchingBrace(out, i);
            if (innerClose < 0 || outerClose < 0 || innerClose >= outerClose) continue;
            if (nextContent(out, innerClose + 1) != outerClose) continue;
            String innerIndent = inner.group(1);
            String outerBodyIndent = outer.group(1) + "    ";
            out.set(i, outer.group(1) + "if (" + stripOuter(outer.group(2).trim()) + " && " + stripOuter(inner.group(2).trim()) + ") {");
            out.remove(outerClose);
            out.remove(innerClose);
            out.remove(innerIndex);
            int bodyStart = innerIndex;
            int bodyEnd = Math.max(bodyStart, innerClose - 1);
            for (int j = bodyStart; j < bodyEnd && j < out.size(); j++) {
                String line = out.get(j);
                if (line.startsWith(innerIndent + "    ")) out.set(j, outerBodyIndent + line.substring((innerIndent + "    ").length()));
            }
            i = Math.max(-1, i - 1);
        }
    }

    private static void simplifyBooleanReturns(ArrayList<String> out) {
        for (int i = 0; i < out.size(); i++) {
            Matcher cond = IF.matcher(out.get(i));
            if (!cond.matches()) continue;
            int close = matchingBrace(out, i);
            if (close != i + 2) continue;
            Matcher inside = RETURN_BOOL.matcher(out.get(i + 1));
            if (!inside.matches()) continue;
            int after = nextContent(out, close + 1);
            if (after < 0) continue;
            Matcher tail = RETURN_BOOL.matcher(out.get(after));
            if (!tail.matches() || !tail.group(1).equals(cond.group(1))) continue;
            boolean a = Boolean.parseBoolean(inside.group(2));
            boolean b = Boolean.parseBoolean(tail.group(2));
            if (a == b) continue;
            String expr = stripOuter(cond.group(2).trim());
            if (!a && b) expr = "!(" + expr + ")";
            out.set(i, cond.group(1) + "return " + expr + ";");
            out.remove(after);
            out.remove(close);
            out.remove(i + 1);
            i = Math.max(-1, i - 1);
        }
    }

    private static void recoverEnhancedFor(ArrayList<String> out) {
        Map<String,String> declarations = declarations(out);
        for (int i = 0; i + 2 < out.size(); i++) {
            Matcher assign = ITERATOR_ASSIGN.matcher(out.get(i));
            if (!assign.matches()) continue;
            String indent = assign.group(1);
            String iterator = assign.group(2);
            String collection = stripOuter(assign.group(3).trim());
            Matcher loop = HAS_NEXT.matcher(out.get(i + 1));
            if (!loop.matches() || !iterator.equals(loop.group(3)) || !loop.group(1).equals(indent)) continue;
            int close = matchingBrace(out, i + 1);
            if (close < 0 || i + 2 >= close) continue;
            Matcher castNext = CAST_NEXT.matcher(out.get(i + 2));
            Matcher plainNext = NEXT.matcher(out.get(i + 2));
            String element;
            String elementType;
            if (castNext.matches() && iterator.equals(castNext.group(4))) {
                element = castNext.group(2);
                elementType = castNext.group(3).trim();
            } else if (plainNext.matches() && iterator.equals(plainNext.group(3))) {
                element = plainNext.group(2);
                elementType = declarations.getOrDefault(element,"java.lang.Object");
            } else continue;
            if (usedOutside(out, iterator, i, close, declarations)) continue;
            out.set(i, indent + "for (" + elementType + " " + element + " : " + collection + ") {");
            out.remove(i + 2);
            out.remove(i + 1);
            removeDeclaration(out, iterator);
            removeDeclaration(out, element);
            declarations = declarations(out);
            i = Math.max(-1, i - 2);
        }
    }

    private static void recoverCountedFor(ArrayList<String> out) {
        for (int i = 0; i + 1 < out.size(); i++) {
            Matcher init = ASSIGN.matcher(out.get(i));
            if (!init.matches()) continue;
            Matcher loop = WHILE.matcher(out.get(i + 1));
            if (!loop.matches() || !Objects.equals(init.group(1),loop.group(1))) continue;
            String variable = init.group(2);
            String condition = stripOuter(loop.group(2).trim());
            if (!containsWord(condition,variable)) continue;
            int close = matchingBrace(out,i + 1);
            if (close < 0) continue;
            int stepIndex = previousContent(out,close - 1);
            if (stepIndex <= i + 1) continue;
            Matcher step = STEP.matcher(out.get(stepIndex));
            String update = null;
            if (step.matches() && variable.equals(step.group(2))) {
                int amount;
                try { amount = Integer.parseInt(step.group(4)); } catch (NumberFormatException ex) { continue; }
                if (amount == 1) update = variable + ("+".equals(step.group(3)) ? "++" : "--");
                else update = variable + " " + step.group(3) + "= " + amount;
            } else if (out.get(stepIndex).trim().equals(variable + "++;") || out.get(stepIndex).trim().equals(variable + "--;")) {
                update = out.get(stepIndex).trim().substring(0,out.get(stepIndex).trim().length()-1);
            }
            if (update == null) continue;
            String initializer = init.group(3).trim();
            out.set(i,init.group(1)+"for ("+variable+" = "+initializer+"; "+condition+"; "+update+") {");
            out.remove(stepIndex);
            out.remove(i + 1);
            i = Math.max(-1,i - 1);
        }
    }

    private static void recoverDiamondOperators(ArrayList<String> out) {
        Map<String,String> types=declarations(out);
        Pattern creation=Pattern.compile("^(\\s*)([A-Za-z_$][\\w$]*)\\s*=\\s*new\\s+([\\w.$]+)\\(\\);$");
        for(int i=0;i<out.size();i++){
            Matcher m=creation.matcher(out.get(i));
            if(!m.matches())continue;
            String declared=types.get(m.group(2));
            if(declared==null||declared.indexOf('<')<0)continue;
            String raw=m.group(3);
            if(!(raw.endsWith("List")||raw.endsWith("Set")||raw.endsWith("Map")||raw.endsWith("Collection")||raw.endsWith("Queue")||raw.endsWith("Deque")))continue;
            out.set(i,m.group(1)+m.group(2)+" = new "+raw+"<>();");
        }
    }

    private static void simplifySteps(ArrayList<String> out) {
        for (int i = 0; i < out.size(); i++) {
            Matcher m = STEP.matcher(out.get(i));
            if (!m.matches() || !"1".equals(m.group(4))) continue;
            out.set(i,m.group(1)+m.group(2)+("+".equals(m.group(3))?"++;":"--;"));
        }
    }

    private static void removeTrailingReturn(ArrayList<String> out) {
        int i = previousContent(out,out.size()-1);
        if (i >= 0 && out.get(i).trim().equals("return;")) out.remove(i);
    }

    private static Map<String,String> declarations(List<String> lines) {
        LinkedHashMap<String,String> out = new LinkedHashMap<>();
        for (String line : lines) {
            Matcher m = DECL.matcher(line);
            if (m.matches() && !Set.of("return","throw","break","continue").contains(m.group(2).trim())) out.putIfAbsent(m.group(3),m.group(2).trim());
        }
        return out;
    }

    private static void removeDeclaration(ArrayList<String> lines,String name) {
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = DECL.matcher(lines.get(i));
            if (m.matches() && name.equals(m.group(3))) {
                lines.remove(i);
                if (i < lines.size() && lines.get(i).isBlank()) {
                    boolean another = i > 0 && DECL.matcher(lines.get(i-1)).matches();
                    if (!another) lines.remove(i);
                }
                return;
            }
        }
    }

    private static boolean usedOutside(List<String> lines,String name,int start,int end,Map<String,String> declarations) {
        for (int i = 0; i < lines.size(); i++) {
            if (i >= start && i <= end) continue;
            if (DECL.matcher(lines.get(i)).matches()) continue;
            if (containsWord(lines.get(i),name)) return true;
        }
        return false;
    }

    private static int matchingBrace(List<String> lines,int open) {
        int depth = 0;
        for (int i = open; i < lines.size(); i++) {
            String line = lines.get(i);
            depth += count(line,'{');
            depth -= count(line,'}');
            if (i > open && depth == 0) return i;
        }
        return -1;
    }

    private static int nextContent(List<String> lines,int from) {
        for (int i = Math.max(0,from); i < lines.size(); i++) if (!lines.get(i).isBlank()) return i;
        return -1;
    }

    private static int previousContent(List<String> lines,int from) {
        for (int i = Math.min(from,lines.size()-1); i >= 0; i--) if (!lines.get(i).isBlank()) return i;
        return -1;
    }

    private static int count(String s,char c) {
        int n=0;
        for(int i=0;i<s.length();i++)if(s.charAt(i)==c)n++;
        return n;
    }

    private static boolean containsWord(String text,String word) {
        return Pattern.compile("(?<![A-Za-z0-9_$])"+Pattern.quote(word)+"(?![A-Za-z0-9_$])").matcher(text).find();
    }

    private static String stripOuter(String text) {
        String s=text;
        while(s.length()>1&&s.charAt(0)=='('&&s.charAt(s.length()-1)==')'&&balancedOuter(s))s=s.substring(1,s.length()-1).trim();
        return s;
    }

    private static boolean balancedOuter(String s) {
        int depth=0;
        for(int i=0;i<s.length();i++){
            char c=s.charAt(i);
            if(c=='(')depth++;
            else if(c==')'){
                depth--;
                if(depth==0&&i<s.length()-1)return false;
            }
        }
        return depth==0;
    }
}
