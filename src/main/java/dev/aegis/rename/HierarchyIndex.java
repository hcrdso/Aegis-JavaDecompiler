package dev.aegis.rename;

import dev.aegis.classfile.*;
import dev.aegis.workspace.*;
import java.util.*;

/** Whole-workspace inheritance/method namespace index inspired by RetroGuard's hierarchy-wide name resolution. */
public final class HierarchyIndex {
    public record MethodId(String owner, String name, String descriptor) {}

    private final Map<String,ClassFile> classes = new LinkedHashMap<>();
    private final Map<MethodId,MethodId> parent = new HashMap<>();
    private final Map<MethodId,Integer> rank = new HashMap<>();

    public HierarchyIndex(Workspace workspace) {
        for (ClassUnit u : workspace.classes()) {
            try { classes.put(u.internalName(), ClassFileParser.parse(u.bytes())); }
            catch (RuntimeException ignored) { }
        }
        for (ClassFile cf : classes.values()) for (MemberInfo m : cf.methods()) {
            if (m.name().startsWith("<")) continue;
            MethodId id = new MethodId(cf.thisClass(), m.name(), m.descriptor());
            parent.put(id,id); rank.put(id,0);
        }
        for (ClassFile cf : classes.values()) connectInherited(cf);
    }

    public Map<String,ClassFile> classes() { return Collections.unmodifiableMap(classes); }
    public ClassFile classFile(String internalName) { return classes.get(internalName); }
    public boolean containsClass(String internalName) { return classes.containsKey(internalName); }

    public Set<String> internalAncestors(String owner) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        collectAncestors(owner,out,new HashSet<>());
        return out;
    }

    private void collectAncestors(String owner, Set<String> out, Set<String> seen) {
        if (!seen.add(owner)) return;
        ClassFile cf = classes.get(owner); if (cf == null) return;
        if (cf.superClass()!=null && classes.containsKey(cf.superClass())) { out.add(cf.superClass()); collectAncestors(cf.superClass(),out,seen); }
        for (String itf : cf.interfaces()) if (classes.containsKey(itf)) { out.add(itf); collectAncestors(itf,out,seen); }
    }

    public boolean hasExternalHierarchyEdge(String owner) {
        ClassFile cf=classes.get(owner); if(cf==null)return true;
        if(cf.superClass()!=null && !"java/lang/Object".equals(cf.superClass()) && !classes.containsKey(cf.superClass())) return true;
        for(String i:cf.interfaces()) if(!classes.containsKey(i)) return true;
        return false;
    }

    public List<MethodId> group(MethodId id) {
        if (!parent.containsKey(id)) return List.of(id);
        MethodId root=find(id); ArrayList<MethodId> out=new ArrayList<>();
        for(MethodId x:parent.keySet()) if(find(x).equals(root)) out.add(x);
        out.sort(Comparator.comparing(MethodId::owner).thenComparing(MethodId::name).thenComparing(MethodId::descriptor));
        return List.copyOf(out);
    }

    public List<List<MethodId>> groups() {
        LinkedHashMap<MethodId,List<MethodId>> g=new LinkedHashMap<>();
        ArrayList<MethodId> ids=new ArrayList<>(parent.keySet());
        ids.sort(Comparator.comparing(MethodId::owner).thenComparing(MethodId::name).thenComparing(MethodId::descriptor));
        for(MethodId id:ids) g.computeIfAbsent(find(id),k->new ArrayList<>()).add(id);
        return List.copyOf(g.values());
    }

    public MemberInfo method(MethodId id) {
        ClassFile cf=classes.get(id.owner()); if(cf==null)return null;
        for(MemberInfo m:cf.methods()) if(m.name().equals(id.name())&&m.descriptor().equals(id.descriptor()))return m;
        return null;
    }

    private void connectInherited(ClassFile cf) {
        for (MemberInfo m : cf.methods()) {
            if (m.name().startsWith("<")) continue;
            MethodId child=new MethodId(cf.thisClass(),m.name(),m.descriptor());
            connectAncestorMethod(child, cf.superClass());
            for(String itf:cf.interfaces()) connectAncestorMethod(child,itf);
        }
    }

    private void connectAncestorMethod(MethodId child,String ancestor) {
        if(ancestor==null)return;
        ClassFile a=classes.get(ancestor); if(a==null)return;
        for(MemberInfo m:a.methods()) if(m.name().equals(child.name())&&m.descriptor().equals(child.descriptor())) {
            MethodId parentId=new MethodId(ancestor,m.name(),m.descriptor());
            if(parent.containsKey(parentId)) union(child,parentId);
            break;
        }
        connectAncestorMethod(child,a.superClass());
        for(String itf:a.interfaces()) connectAncestorMethod(child,itf);
    }

    private MethodId find(MethodId x) {
        MethodId p=parent.get(x); if(p==null)return x;
        if(!p.equals(x)){p=find(p);parent.put(x,p);}return p;
    }
    private void union(MethodId a,MethodId b) {
        MethodId ra=find(a),rb=find(b);if(ra.equals(rb))return;
        int aa=rank.getOrDefault(ra,0),bb=rank.getOrDefault(rb,0);
        if(aa<bb)parent.put(ra,rb); else if(aa>bb)parent.put(rb,ra); else{parent.put(rb,ra);rank.put(ra,aa+1);}
    }
}
