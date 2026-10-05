package dev.aegis.rename;

import java.util.*;

/** Semantic mappings plus confidence/reason metadata for every inferred rename. */
public final class MappingSet {
    public record MemberKey(String owner, String name, String descriptor) {}
    public record Decision(String target, int confidence, String reason) {
        public Decision {
            confidence = Math.max(0, Math.min(100, confidence));
            reason = reason == null ? "" : reason;
        }
    }

    private final LinkedHashMap<String, Decision> classDecisions = new LinkedHashMap<>();
    private final LinkedHashMap<MemberKey, Decision> fieldDecisions = new LinkedHashMap<>();
    private final LinkedHashMap<MemberKey, Decision> methodDecisions = new LinkedHashMap<>();

    public void mapClass(String from, String to) { mapClass(from, to, 50, "heuristic rename"); }
    public void mapField(String owner, String name, String desc, String to) { mapField(owner, name, desc, to, 50, "heuristic rename"); }
    public void mapMethod(String owner, String name, String desc, String to) { mapMethod(owner, name, desc, to, 50, "heuristic rename"); }

    public void mapClass(String from, String to, int confidence, String reason) {
        if (!Objects.equals(from, to)) classDecisions.put(from, new Decision(to, confidence, reason));
    }
    public void mapField(String owner, String name, String desc, String to, int confidence, String reason) {
        if (!Objects.equals(name, to)) fieldDecisions.put(new MemberKey(owner, name, desc), new Decision(to, confidence, reason));
    }
    public void mapMethod(String owner, String name, String desc, String to, int confidence, String reason) {
        if (!Objects.equals(name, to)) methodDecisions.put(new MemberKey(owner, name, desc), new Decision(to, confidence, reason));
    }

    public String className(String internal) { Decision d = classDecisions.get(internal); return d == null ? internal : d.target(); }
    public String fieldName(String owner, String name, String desc) { Decision d = fieldDecisions.get(new MemberKey(owner, name, desc)); return d == null ? name : d.target(); }
    public String methodName(String owner, String name, String desc) { Decision d = methodDecisions.get(new MemberKey(owner, name, desc)); return d == null ? name : d.target(); }

    public Decision classDecision(String internal) { return classDecisions.get(internal); }
    public Decision fieldDecision(String owner, String name, String desc) { return fieldDecisions.get(new MemberKey(owner, name, desc)); }
    public Decision methodDecision(String owner, String name, String desc) { return methodDecisions.get(new MemberKey(owner, name, desc)); }

    public Map<String, String> classes() {
        LinkedHashMap<String,String> out = new LinkedHashMap<>();
        classDecisions.forEach((k,v) -> out.put(k,v.target()));
        return Collections.unmodifiableMap(out);
    }
    public Map<MemberKey, String> fields() {
        LinkedHashMap<MemberKey,String> out = new LinkedHashMap<>();
        fieldDecisions.forEach((k,v) -> out.put(k,v.target()));
        return Collections.unmodifiableMap(out);
    }
    public Map<MemberKey, String> methods() {
        LinkedHashMap<MemberKey,String> out = new LinkedHashMap<>();
        methodDecisions.forEach((k,v) -> out.put(k,v.target()));
        return Collections.unmodifiableMap(out);
    }

    public Map<String, Decision> classDecisions() { return Collections.unmodifiableMap(classDecisions); }
    public Map<MemberKey, Decision> fieldDecisions() { return Collections.unmodifiableMap(fieldDecisions); }
    public Map<MemberKey, Decision> methodDecisions() { return Collections.unmodifiableMap(methodDecisions); }
    public boolean isEmpty() { return classDecisions.isEmpty() && fieldDecisions.isEmpty() && methodDecisions.isEmpty(); }

    public String mapJavaType(String javaType) {
        int dims = 0;
        String base = javaType;
        while (base.endsWith("[]")) { dims++; base = base.substring(0, base.length() - 2); }
        String internal = base.replace('.', '/');
        Decision mapped = classDecisions.get(internal);
        if (mapped != null) base = mapped.target().replace('/', '.').replace('$', '.');
        return base + "[]".repeat(dims);
    }

    public String toTinyV2() {
        StringBuilder b = new StringBuilder("tiny\t2\t0\toriginal\taegis\n");
        for (var e : classDecisions.entrySet()) b.append("c\t").append(e.getKey()).append('\t').append(e.getValue().target()).append('\n');
        for (var e : fieldDecisions.entrySet()) {
            MemberKey k = e.getKey(); b.append("f\t").append(k.owner()).append('\t').append(k.descriptor()).append('\t').append(k.name()).append('\t').append(e.getValue().target()).append('\n');
        }
        for (var e : methodDecisions.entrySet()) {
            MemberKey k = e.getKey(); b.append("m\t").append(k.owner()).append('\t').append(k.descriptor()).append('\t').append(k.name()).append('\t').append(e.getValue().target()).append('\n');
        }
        return b.toString();
    }

    public String toProGuard() {
        StringBuilder b = new StringBuilder();
        for (var ce : classDecisions.entrySet()) {
            String owner = ce.getKey();
            b.append(owner.replace('/','.')).append(" -> ").append(ce.getValue().target().replace('/','.')).append(":\n");
            for (var e : fieldDecisions.entrySet()) if (e.getKey().owner().equals(owner)) {
                MemberKey k=e.getKey(); b.append("    ").append(k.descriptor()).append(' ').append(k.name()).append(" -> ").append(e.getValue().target()).append('\n');
            }
            for (var e : methodDecisions.entrySet()) if (e.getKey().owner().equals(owner)) {
                MemberKey k=e.getKey(); b.append("    ").append(k.descriptor()).append(' ').append(k.name()).append(" -> ").append(e.getValue().target()).append('\n');
            }
        }
        return b.toString();
    }

    public String toJson() {
        StringBuilder b=new StringBuilder("{\n  \"format\": \"aegis-mappings-v1\",\n");
        b.append("  \"classes\": [\n"); int n=0;
        for(var e:classDecisions.entrySet()){if(n++>0)b.append(",\n");appendJsonDecision(b,"    ",e.getKey(),null,null,e.getValue());} b.append("\n  ],\n");
        b.append("  \"fields\": [\n"); n=0;
        for(var e:fieldDecisions.entrySet()){if(n++>0)b.append(",\n");MemberKey k=e.getKey();appendJsonDecision(b,"    ",k.owner(),k.name(),k.descriptor(),e.getValue());} b.append("\n  ],\n");
        b.append("  \"methods\": [\n"); n=0;
        for(var e:methodDecisions.entrySet()){if(n++>0)b.append(",\n");MemberKey k=e.getKey();appendJsonDecision(b,"    ",k.owner(),k.name(),k.descriptor(),e.getValue());} b.append("\n  ]\n}\n");
        return b.toString();
    }

    private static void appendJsonDecision(StringBuilder b,String indent,String owner,String name,String descriptor,Decision d){
        b.append(indent).append("{\"owner\":\"").append(json(owner)).append("\"");
        if(name!=null)b.append(",\"name\":\"").append(json(name)).append("\"");
        if(descriptor!=null)b.append(",\"descriptor\":\"").append(json(descriptor)).append("\"");
        b.append(",\"target\":\"").append(json(d.target())).append("\",\"confidence\":").append(d.confidence())
                .append(",\"reason\":\"").append(json(d.reason())).append("\"}");
    }
    private static String json(String s){return s==null?"":s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r").replace("\t","\\t");}

    public String summary() {
        StringBuilder b = new StringBuilder();
        b.append("Aegis semantic rename plan\n\nClasses: ").append(classDecisions.size())
                .append("\nFields: ").append(fieldDecisions.size()).append("\nMethods: ").append(methodDecisions.size()).append('\n');
        int high=0,medium=0,low=0; ArrayList<Decision> all=new ArrayList<>(); all.addAll(classDecisions.values());all.addAll(fieldDecisions.values());all.addAll(methodDecisions.values());
        for(Decision d:all){if(d.confidence()>=85)high++;else if(d.confidence()>=60)medium++;else low++;}
        b.append("Confidence: ").append(high).append(" high / ").append(medium).append(" medium / ").append(low).append(" low\n\n");
        if (!classDecisions.isEmpty()) {
            b.append("[Classes]\n");
            classDecisions.forEach((k,d) -> b.append(k).append(" -> ").append(d.target()).append("  [").append(d.confidence()).append("%] ").append(d.reason()).append('\n'));
            b.append('\n');
        }
        if (!fieldDecisions.isEmpty()) {
            b.append("[Fields]\n");
            fieldDecisions.forEach((k,d) -> b.append(k.owner()).append('.').append(k.name()).append(' ').append(k.descriptor()).append(" -> ").append(d.target()).append("  [").append(d.confidence()).append("%] ").append(d.reason()).append('\n'));
            b.append('\n');
        }
        if (!methodDecisions.isEmpty()) {
            b.append("[Methods]\n");
            methodDecisions.forEach((k,d) -> b.append(k.owner()).append('.').append(k.name()).append(k.descriptor()).append(" -> ").append(d.target()).append("  [").append(d.confidence()).append("%] ").append(d.reason()).append('\n'));
        }
        return b.toString();
    }
}
