package dev.aegis.util;

import java.nio.charset.*;
import java.util.Locale;

public final class ResourcePreview {
    private ResourcePreview() {}
    public static String preview(String name, byte[] bytes) {
        int max = Math.min(bytes.length, 512 * 1024);
        String lower = name.toLowerCase(Locale.ROOT);
        boolean text = lower.endsWith(".txt") || lower.endsWith(".json") || lower.endsWith(".xml") || lower.endsWith(".mf")
                || lower.endsWith(".properties") || lower.endsWith(".yml") || lower.endsWith(".yaml") || lower.endsWith(".csv")
                || lower.startsWith("meta-inf/services/");
        if (text) return new String(bytes, 0, max, StandardCharsets.UTF_8) + (bytes.length > max ? "\n\n... truncated ..." : "");
        StringBuilder b = new StringBuilder("Resource: ").append(name).append("\nSize: ").append(bytes.length).append(" bytes\n\n");
        int shown = Math.min(bytes.length, 1024);
        for (int i = 0; i < shown; i += 16) {
            b.append(String.format("%08x  ", i));
            for (int j = 0; j < 16; j++) b.append(i + j < shown ? String.format("%02x ", bytes[i+j] & 0xFF) : "   ");
            b.append(" ");
            for (int j = 0; j < 16 && i + j < shown; j++) {
                int c = bytes[i+j] & 0xFF; b.append(c >= 32 && c < 127 ? (char)c : '.');
            }
            b.append('\n');
        }
        if (bytes.length > shown) b.append("... truncated ...\n");
        return b.toString();
    }
}
