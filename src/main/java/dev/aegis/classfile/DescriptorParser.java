package dev.aegis.classfile;

import java.util.*;

public final class DescriptorParser {
    public record MethodDescriptor(List<String> parameterTypes, String returnType, List<Integer> slotWidths) {}

    private DescriptorParser() {}

    public static String fieldType(String descriptor) {
        Cursor c = new Cursor(descriptor);
        String type = parseType(c);
        if (!c.end()) throw new IllegalArgumentException("Trailing descriptor data: " + descriptor);
        return type;
    }

    public static MethodDescriptor method(String descriptor) {
        Cursor c = new Cursor(descriptor);
        if (c.next() != '(') throw new IllegalArgumentException("Invalid method descriptor: " + descriptor);
        ArrayList<String> params = new ArrayList<>();
        ArrayList<Integer> widths = new ArrayList<>();
        while (c.peek() != ')') {
            int start = c.pos;
            String type = parseType(c);
            params.add(type);
            char first = descriptor.charAt(start);
            widths.add(first == 'J' || first == 'D' ? 2 : 1);
        }
        c.next();
        String ret = parseType(c);
        if (!c.end()) throw new IllegalArgumentException("Trailing method descriptor data: " + descriptor);
        return new MethodDescriptor(List.copyOf(params), ret, List.copyOf(widths));
    }

    public static String simpleClassName(String internal) {
        if (internal == null) return "Object";
        if (internal.startsWith("[")) return fieldType(internal);
        int slash = internal.lastIndexOf('/');
        String s = slash >= 0 ? internal.substring(slash + 1) : internal;
        return s.replace('$', '.');
    }

    public static String binaryClassName(String internal) {
        if (internal == null) return "java.lang.Object";
        if (internal.startsWith("[")) return fieldType(internal);
        return internal.replace('/', '.').replace('$', '.');
    }

    public static String defaultValue(String type) {
        return switch (type) {
            case "void" -> "";
            case "boolean" -> "false";
            case "byte", "short", "int", "char" -> "0";
            case "long" -> "0L";
            case "float" -> "0.0f";
            case "double" -> "0.0d";
            default -> "null";
        };
    }

    private static String parseType(Cursor c) {
        char ch = c.next();
        return switch (ch) {
            case 'V' -> "void";
            case 'Z' -> "boolean";
            case 'B' -> "byte";
            case 'C' -> "char";
            case 'S' -> "short";
            case 'I' -> "int";
            case 'J' -> "long";
            case 'F' -> "float";
            case 'D' -> "double";
            case 'L' -> {
                int start = c.pos;
                while (c.peek() != ';') c.pos++;
                String internal = c.s.substring(start, c.pos);
                c.pos++;
                yield binaryClassName(internal);
            }
            case '[' -> parseType(c) + "[]";
            default -> throw new IllegalArgumentException("Bad descriptor character '" + ch + "' in " + c.s);
        };
    }

    private static final class Cursor {
        final String s;
        int pos;
        Cursor(String s) { this.s = Objects.requireNonNull(s); }
        boolean end() { return pos == s.length(); }
        char next() {
            if (pos >= s.length()) throw new IllegalArgumentException("Unexpected end of descriptor: " + s);
            return s.charAt(pos++);
        }
        char peek() {
            if (pos >= s.length()) throw new IllegalArgumentException("Unexpected end of descriptor: " + s);
            return s.charAt(pos);
        }
    }
}
