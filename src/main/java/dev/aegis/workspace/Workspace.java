package dev.aegis.workspace;

import dev.aegis.classfile.*;
import dev.aegis.deobfuscate.NativeBytecodeCleaner;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;

public final class Workspace {
    private static final long MAX_ENTRY_BYTES = 128L * 1024L * 1024L;
    private static final long MAX_TOTAL_BYTES = 768L * 1024L * 1024L;

    private final Path source;
    private final LinkedHashMap<String, byte[]> entries;
    private final List<String> diagnostics;

    private Workspace(Path source, LinkedHashMap<String, byte[]> entries, List<String> diagnostics) {
        this.source = source;
        this.entries = entries;
        this.diagnostics = diagnostics;
    }

    public static Workspace load(Path source) throws IOException {
        Objects.requireNonNull(source, "source");
        Path p = source.toAbsolutePath().normalize();
        if (!Files.isRegularFile(p)) throw new FileNotFoundException(p.toString());
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        ArrayList<String> diagnostics = new ArrayList<>();
        String lower = p.getFileName().toString().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".class")) {
            byte[] bytes = Files.readAllBytes(p);
            ClassFile cf = ClassFileParser.parse(bytes);
            entries.put(cf.thisClass() + ".class", bytes);
        } else if (lower.endsWith(".jar") || lower.endsWith(".zip") || lower.endsWith(".jmod") || lower.endsWith(".war")) {
            loadArchive(p, entries, diagnostics);
        } else throw new IOException("Unsupported input. Open a .jar, .zip, .jmod, .war or .class file.");
        if (entries.keySet().stream().noneMatch(Workspace::isClassEntry)) throw new IOException("No .class files found.");
        return new Workspace(p, entries, diagnostics);
    }

    private static void loadArchive(Path archive, LinkedHashMap<String, byte[]> output, List<String> diagnostics) throws IOException {
        long total = 0;
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            Enumeration<? extends ZipEntry> e = zip.entries();
            while (e.hasMoreElements()) {
                ZipEntry entry = e.nextElement();
                if (entry.isDirectory()) continue;
                String name = normalizeEntryName(entry.getName());
                if (name == null) { diagnostics.add("Skipped unsafe path: " + entry.getName()); continue; }
                if (output.containsKey(name)) { diagnostics.add("Ignored duplicate entry: " + name); continue; }
                if (entry.getSize() > MAX_ENTRY_BYTES) throw new IOException("Archive entry too large: " + name);
                byte[] bytes;
                try (InputStream in = zip.getInputStream(entry)) { bytes = readLimited(in, MAX_ENTRY_BYTES); }
                total += bytes.length;
                if (total > MAX_TOTAL_BYTES) throw new IOException("Archive expands beyond 768 MiB safety limit.");
                if (isClassEntry(name)) {
                    try { ClassFileParser.parse(bytes); }
                    catch (RuntimeException ex) { diagnostics.add("Class parser warning for " + name + ": " + safe(ex)); }
                }
                output.put(name, bytes);
            }
        }
    }

    private static byte[] readLimited(InputStream in, long limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long total = 0;
        for (int n; (n = in.read(buf)) >= 0;) {
            total += n;
            if (total > limit) throw new IOException("Archive entry exceeded size limit while decompressing.");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static String normalizeEntryName(String raw) {
        String n = raw.replace('\\', '/');
        if (n.startsWith("/") || n.equals("..") || n.contains("../")) return null;
        return n;
    }

    public synchronized List<ClassUnit> classes() {
        ArrayList<ClassUnit> out = new ArrayList<>();
        for (Map.Entry<String, byte[]> e : entries.entrySet()) if (isClassEntry(e.getKey())) {
            try { out.add(ClassUnit.from(e.getKey(), e.getValue())); } catch (RuntimeException ignored) {}
        }
        out.sort(Comparator.comparing(ClassUnit::internalName));
        return List.copyOf(out);
    }

    public synchronized Optional<ClassUnit> classByEntry(String entry) {
        byte[] bytes = entries.get(entry);
        if (bytes == null || !isClassEntry(entry)) return Optional.empty();
        try { return Optional.of(ClassUnit.from(entry, bytes)); }
        catch (RuntimeException ex) { return Optional.empty(); }
    }

    public synchronized byte[] entryBytes(String entry) {
        byte[] b = entries.get(entry);
        return b == null ? null : b.clone();
    }

    public synchronized List<String> entryNames() { return List.copyOf(entries.keySet()); }
    /** Locate an exact Java source resource bundled in the opened archive, if present. */
    /** Attach a separate source JAR/ZIP so exact comments and source metadata can be recovered. */
    public synchronized int attachSources(Path archive) throws IOException {
        Path p=archive.toAbsolutePath().normalize();if(!Files.isRegularFile(p))throw new FileNotFoundException(p.toString());
        int added=0;long total=0;
        try(ZipFile zip=new ZipFile(p.toFile())){Enumeration<? extends ZipEntry> it=zip.entries();while(it.hasMoreElements()){ZipEntry ze=it.nextElement();if(ze.isDirectory()||!ze.getName().toLowerCase(Locale.ROOT).endsWith(".java"))continue;String name=normalizeEntryName(ze.getName());if(name==null||entries.containsKey(name))continue;try(InputStream in=zip.getInputStream(ze)){byte[] b=readLimited(in,MAX_ENTRY_BYTES);total+=b.length;if(total>MAX_TOTAL_BYTES)throw new IOException("Source archive expands beyond safety limit.");entries.put(name,b);added++;}}}
        return added;
    }

    public synchronized Optional<String> bundledSource(String internalName, String sourceFileName) {
        if (internalName == null) return Optional.empty();
        String outer = internalName; int dollar = outer.indexOf('$'); if (dollar >= 0) outer = outer.substring(0, dollar);
        String expected = outer + ".java";
        byte[] exact = entries.get(expected);
        if (exact != null) return Optional.of(new String(exact, java.nio.charset.StandardCharsets.UTF_8));
        if (sourceFileName != null && !sourceFileName.isBlank()) {
            int slash = outer.lastIndexOf('/'); String pkg = slash < 0 ? "" : outer.substring(0, slash + 1);
            exact = entries.get(pkg + sourceFileName);
            if (exact != null) return Optional.of(new String(exact, java.nio.charset.StandardCharsets.UTF_8));
            String suffix = "/" + sourceFileName; byte[] found = null;
            for (var e : entries.entrySet()) if (e.getKey().endsWith(suffix) || e.getKey().equals(sourceFileName)) { if (found != null) return Optional.empty(); found=e.getValue(); }
            if (found != null) return Optional.of(new String(found, java.nio.charset.StandardCharsets.UTF_8));
        }
        return Optional.empty();
    }

    public Path source() { return source; }
    public List<String> diagnostics() { return List.copyOf(diagnostics); }

    public synchronized NativeBytecodeCleaner.CleanResult exportDeobfuscated(Path output) throws IOException {
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        NativeBytecodeCleaner cleaner = new NativeBytecodeCleaner();
        int methods = 0, rewrites = 0; ArrayList<String> notes = new ArrayList<>();
        try (JarOutputStream jar = new JarOutputStream(new BufferedOutputStream(Files.newOutputStream(output)))) {
            HashSet<String> written = new HashSet<>();
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                String upper = e.getKey().toUpperCase(Locale.ROOT);
                if (upper.startsWith("META-INF/") && (upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC"))) {
                    notes.add("Removed invalidated signature: " + e.getKey());
                    continue;
                }
                if (!written.add(e.getKey())) continue;
                byte[] bytes = e.getValue();
                if (isClassEntry(e.getKey())) {
                    try {
                        NativeBytecodeCleaner.CleanResult r = cleaner.clean(bytes);
                        bytes = r.bytes(); methods += r.methodsChanged(); rewrites += r.instructionsRewritten(); notes.addAll(r.notes());
                    } catch (RuntimeException ex) { notes.add("Cleaner skipped " + e.getKey() + ": " + safe(ex)); }
                }
                JarEntry je = new JarEntry(e.getKey()); je.setTime(0L); jar.putNextEntry(je); jar.write(bytes); jar.closeEntry();
            }
        }
        return new NativeBytecodeCleaner.CleanResult(new byte[0], methods, rewrites, List.copyOf(notes));
    }

    public synchronized void exportCopy(Path output) throws IOException {
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        try (JarOutputStream jar = new JarOutputStream(new BufferedOutputStream(Files.newOutputStream(output)))) {
            HashSet<String> written = new HashSet<>();
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                if (!written.add(e.getKey())) continue;
                JarEntry je = new JarEntry(e.getKey());
                je.setTime(0L);
                jar.putNextEntry(je);
                jar.write(e.getValue());
                jar.closeEntry();
            }
        }
    }

    public static boolean isClassEntry(String name) { return name.toLowerCase(Locale.ROOT).endsWith(".class"); }
    private static String safe(Throwable t) {
        String m = t.getMessage(); return m == null ? t.getClass().getSimpleName() : m;
    }
}
