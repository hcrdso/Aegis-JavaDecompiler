package dev.aegis.rename;

import dev.aegis.classfile.*;
import dev.aegis.util.JavaNames;
import dev.aegis.workspace.*;
import java.util.*;

/**
 * Whole-program semantic rename planner.
 *
 * The important rule is that method names are planned across an inheritance namespace,
 * not independently per class. This mirrors the constraint an obfuscator must respect
 * for overrides/interfaces and makes deobfuscation mappings consistent.
 */
public final class RenamerPlanner {
    private final SemanticNameEngine semantics = new SemanticNameEngine();

    public MappingSet plan(Workspace workspace, boolean aggressive) {
        MappingSet mappings = new MappingSet();
        HierarchyIndex hierarchy = new HierarchyIndex(workspace);
        ReflectionUsageAnalyzer.Result reflection = new ReflectionUsageAnalyzer().scan(hierarchy.classes().values());

        planClasses(hierarchy, reflection, aggressive, mappings);
        planFields(hierarchy, reflection, aggressive, mappings);
        planMethods(hierarchy, reflection, aggressive, mappings);
        return mappings;
    }

    private void planClasses(HierarchyIndex hierarchy, ReflectionUsageAnalyzer.Result reflection,
                             boolean aggressive, MappingSet mappings) {
        Map<String,Set<String>> usedByPackage = new HashMap<>();
        for (String name : hierarchy.classes().keySet()) {
            String pkg=packagePrefix(name), simple=simple(name);
            usedByPackage.computeIfAbsent(pkg,k->new HashSet<>()).add(simple);
        }
        int ordinal=1;
        for (ClassFile cf : hierarchy.classes().values()) {
            String simple=simple(cf.thisClass());
            boolean suspicious=JavaNames.isLikelyObfuscated(simple);
            if(!suspicious && !aggressive)continue;
            if(!aggressive && reflection.classNames().contains(cf.thisClass()))continue;
            if(!suspicious && (AccessFlags.has(cf.accessFlags(),AccessFlags.PUBLIC)||AccessFlags.has(cf.accessFlags(),AccessFlags.PROTECTED)))continue;
            SemanticNameEngine.Candidate c=semantics.classCandidate(cf,ordinal++);
            String pkg=packagePrefix(cf.thisClass());
            String targetSimple=uniqueClassName(JavaNames.sanitize(c.name(),"RecoveredClass"),usedByPackage.computeIfAbsent(pkg,k->new HashSet<>()));
            usedByPackage.get(pkg).add(targetSimple);
            mappings.mapClass(cf.thisClass(),pkg+targetSimple,c.confidence(),c.reason());
        }
    }

    private void planFields(HierarchyIndex hierarchy, ReflectionUsageAnalyzer.Result reflection,
                            boolean aggressive, MappingSet mappings) {
        for(ClassFile cf:hierarchy.classes().values()){
            HashSet<String> used=new HashSet<>();
            for(MemberInfo f:cf.fields()) if(!isLikelyObfuscatedFieldName(f.name()))used.add(f.name());
            for(MemberInfo f:cf.fields()){
                if(isSpecialField(f))continue;
                boolean suspicious=isLikelyObfuscatedFieldName(f.name());
                boolean rename=suspicious||(aggressive&&AccessFlags.has(f.accessFlags(),AccessFlags.PRIVATE));
                if(!rename)continue;
                if(!aggressive&&reflection.memberNames().contains(f.name()))continue;
                SemanticNameEngine.Candidate c=semantics.fieldCandidate(cf,f);
                String base=JavaNames.sanitize(c.name(),"value");
                String target=unique(base,used);
                used.add(target);
                int confidence=c.confidence();
                String reason=c.reason();
                if(JavaNames.isKeyword(f.name())){confidence=Math.max(confidence,92);reason="JVM keyword identifier; "+reason;}
                mappings.mapField(cf.thisClass(),f.name(),f.descriptor(),target,confidence,reason);
            }
        }
    }

    private void planMethods(HierarchyIndex hierarchy, ReflectionUsageAnalyzer.Result reflection,
                             boolean aggressive, MappingSet mappings) {
        // Java source cannot overload solely by return type. Track target-name + argument-list per owner.
        Map<String,Set<String>> usedSignatures=new HashMap<>();
        for(ClassFile cf:hierarchy.classes().values()){
            Set<String> used=usedSignatures.computeIfAbsent(cf.thisClass(),k->new HashSet<>());
            for(MemberInfo m:cf.methods()) if(!m.name().startsWith("<")&&!isLikelyObfuscatedMethodName(m.name()))
                used.add(m.name()+argDescriptor(m.descriptor()));
        }

        int fallbackId=1;
        for(List<HierarchyIndex.MethodId> group:hierarchy.groups()){
            if(group.isEmpty())continue;
            boolean anySuspicious=false, anyRename=false, preserve=false;
            String meaningfulExisting=null;
            for(HierarchyIndex.MethodId id:group){
                MemberInfo m=hierarchy.method(id); if(m==null)continue;
                if(isWellKnownMethod(m.name(),m.descriptor())){preserve=true;break;}
                boolean suspicious=isLikelyObfuscatedMethodName(m.name()); anySuspicious|=suspicious;
                if(!suspicious&&meaningfulExisting==null)meaningfulExisting=m.name();
                boolean rename=suspicious||(aggressive&&AccessFlags.has(m.accessFlags(),AccessFlags.PRIVATE));
                anyRename|=rename;
                if(!aggressive&&reflection.memberNames().contains(m.name()))preserve=true;
                // Conservative rule: a public/protected method that crosses outside the loaded hierarchy may be API.
                if(!aggressive&&!suspicious&&!AccessFlags.has(m.accessFlags(),AccessFlags.PRIVATE)&&hierarchy.hasExternalHierarchyEdge(id.owner())) preserve=true;
            }
            if(preserve||!anyRename)continue;

            SemanticNameEngine.Candidate best=null;
            if(meaningfulExisting!=null){
                best=new SemanticNameEngine.Candidate(meaningfulExisting,98,"meaningful name retained elsewhere in override group");
            } else {
                for(HierarchyIndex.MethodId id:group){
                    MemberInfo m=hierarchy.method(id);if(m==null)continue;
                    SemanticNameEngine.Candidate c=semantics.methodCandidate(hierarchy.classFile(id.owner()),m,mappings);
                    if(best==null||c.confidence()>best.confidence())best=c;
                }
            }
            if(best==null)best=new SemanticNameEngine.Candidate("method"+String.format("%03d",fallbackId++),30,"fallback semantic name");
            String base=JavaNames.sanitize(best.name(),"method"+String.format("%03d",fallbackId++));

            // One target name must be valid in every owner participating in the override group.
            String target=base;int suffix=2;
            while(conflicts(group,target,usedSignatures))target=base+(suffix++);

            for(HierarchyIndex.MethodId id:group){
                MemberInfo m=hierarchy.method(id);if(m==null)continue;
                boolean suspicious=isLikelyObfuscatedMethodName(m.name());
                boolean rename=suspicious||(aggressive&&AccessFlags.has(m.accessFlags(),AccessFlags.PRIVATE));
                if(!rename)continue;
                int confidence=best.confidence();String reason=best.reason();
                if(group.size()>1){confidence=Math.min(100,confidence+4);reason += "; inheritance group of "+group.size()+" method(s)";}
                if(JavaNames.isKeyword(m.name())){confidence=Math.max(confidence,94);reason="RetroGuard-style JVM keyword identifier; "+reason;}
                mappings.mapMethod(id.owner(),id.name(),id.descriptor(),target,confidence,reason);
                usedSignatures.computeIfAbsent(id.owner(),k->new HashSet<>()).add(target+argDescriptor(id.descriptor()));
            }
        }

        // Bridge methods often use a different descriptor, so connect them to the meaningful target they invoke.
        for(ClassFile cf:hierarchy.classes().values())for(MemberInfo m:cf.methods()){
            if(!AccessFlags.has(m.accessFlags(),AccessFlags.BRIDGE)||m.name().startsWith("<"))continue;
            if(mappings.methodDecision(cf.thisClass(),m.name(),m.descriptor())!=null)continue;
            String target=bridgeTarget(cf,m,mappings);
            if(target!=null&&!target.equals(m.name()))mappings.mapMethod(cf.thisClass(),m.name(),m.descriptor(),target,91,"synthetic bridge forwards to "+target);
        }
    }

    private static boolean conflicts(List<HierarchyIndex.MethodId> group,String target,Map<String,Set<String>> used){
        for(HierarchyIndex.MethodId id:group)if(used.getOrDefault(id.owner(),Set.of()).contains(target+argDescriptor(id.descriptor())))return true;
        return false;
    }

    private static String bridgeTarget(ClassFile cf,MemberInfo m,MappingSet mappings){
        AttributeInfo ca=m.attribute("Code");if(ca==null)return null;
        try{
            CodeAttribute code=CodeAttribute.parse(ca,cf.constantPool());
            for(Instruction i:BytecodeDecoder.decode(code.code()))if(i.opcode()>=182&&i.opcode()<=185){
                ConstantPool.MemberRef r=cf.constantPool().memberRef(i.u2(0));
                if(r.owner().equals(cf.thisClass())&&!r.name().equals(m.name()))return mappings.methodName(r.owner(),r.name(),r.descriptor());
            }
        }catch(RuntimeException ignored){}
        return null;
    }

    private static String unique(String base,Set<String> used){if(!used.contains(base))return base;for(int i=2;;i++){String x=base+i;if(!used.contains(x))return x;}}
    private static String uniqueClassName(String base,Set<String> used){return unique(base,used);}
    private static String argDescriptor(String desc){int p=desc.indexOf(')');return p<0?desc:desc.substring(0,p+1);}
    private static String packagePrefix(String n){int x=n.lastIndexOf('/');return x<0?"":n.substring(0,x+1);}
    private static String simple(String n){int x=n.lastIndexOf('/');return x<0?n:n.substring(x+1);}
    private static boolean isLikelyObfuscatedFieldName(String n){
        if(n==null||n.isEmpty()||JavaNames.isKeyword(n)||!JavaNames.isValidIdentifier(n))return true;
        if(n.length()==1&&!Set.of("x","y","z").contains(n))return true;
        return JavaNames.isLikelyObfuscated(n);
    }
    private static boolean isLikelyObfuscatedMethodName(String n){
        if(n!=null&&(n.startsWith("lambda$")||n.startsWith("access$")))return true;
        if(n==null||n.isEmpty()||JavaNames.isKeyword(n)||!JavaNames.isValidIdentifier(n))return true;
        if(n.length()==1)return true;
        if(n.length()==2&&!Set.of("of","id","on","up").contains(n))return true;
        return JavaNames.isLikelyObfuscated(n);
    }
    private static boolean isSpecialField(MemberInfo f){return f.name().equals("serialVersionUID")||f.name().equals("$assertionsDisabled");}
    private static boolean isWellKnownMethod(String n,String d){
        return n.equals("$deserializeLambda$")||(n.equals("toString")&&d.equals("()Ljava/lang/String;"))||(n.equals("hashCode")&&d.equals("()I"))||(n.equals("equals")&&d.equals("(Ljava/lang/Object;)Z"))
                ||n.equals("main")||n.equals("readObject")||n.equals("writeObject")||n.equals("readResolve")||n.equals("writeReplace")||n.equals("values")||n.equals("valueOf");
    }
}
