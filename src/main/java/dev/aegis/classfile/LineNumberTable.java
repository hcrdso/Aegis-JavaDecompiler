package dev.aegis.classfile;

import java.util.*;

/** Parsed LineNumberTable debug metadata. */
public final class LineNumberTable {
    public record Entry(int startPc, int line) {}
    private final List<Entry> entries;

    private LineNumberTable(List<Entry> entries) { this.entries = List.copyOf(entries); }

    public static LineNumberTable from(CodeAttribute code) {
        ArrayList<Entry> out = new ArrayList<>();
        for (AttributeInfo a : code.attributes()) {
            if (!"LineNumberTable".equals(a.name())) continue;
            try {
                ByteReader r = a.reader();
                int n = r.u2();
                for (int i = 0; i < n; i++) out.add(new Entry(r.u2(), r.u2()));
            } catch (RuntimeException ignored) { }
        }
        out.sort(Comparator.comparingInt(Entry::startPc));
        return new LineNumberTable(out);
    }

    public List<Entry> entries() { return entries; }
    public boolean isEmpty() { return entries.isEmpty(); }

    public int minLine() {
        int min = Integer.MAX_VALUE;
        for (Entry e : entries) min = Math.min(min, e.line());
        return min == Integer.MAX_VALUE ? -1 : min;
    }

    public int maxLine() {
        int max = -1;
        for (Entry e : entries) max = Math.max(max, e.line());
        return max;
    }

    public int lineAt(int pc) {
        int line = -1;
        for (Entry e : entries) {
            if (e.startPc() > pc) break;
            line = e.line();
        }
        return line;
    }
}
