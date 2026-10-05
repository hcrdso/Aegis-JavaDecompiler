package dev.aegis.rename;

import dev.aegis.classfile.*;
import dev.aegis.decompile.SourceSymbolRecovery;
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

        planClasses(workspace, hierarchy, reflection, aggressive, mappings);
        planFields(workspace, hierarchy, reflection, aggressive, mappings);
        planMethods(workspace, hierarchy, reflection, aggressive, mappings);
        return mappings;
    }

    private void planClasses(Workspace workspace, HierarchyIndex hierarchy, ReflectionUsageAnalyzer.Result reflection,
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
            SemanticNameEngine.Candidate c=sourceClassCandidate(workspace,cf);
            if(c==null)c=semantics.classCandidate(cf,ordinal++);
            String pkg=packagePrefix(cf.thisClass());
            String targetSimple=uniqueClassName(JavaNames.sanitize(c.name(),"RecoveredClass"),usedByPackage.computeIfAbsent(pkg,k->new HashSet<>()));
            usedByPackage.get(pkg).add(targetSimple);
            mappings.mapClass(cf.thisClass(),pkg+targetSimple,c.confidence(),c.reason());
        }
    }

    private static SemanticNameEngine.Candidate sourceClassCandidate(Workspace workspace, ClassFile cf) {
        if (cf.thisClass().contains("$")) return null;
        String sourceFile = DebugMetadata.sourceFile(cf);
        if (sourceFile == null || !sourceFile.endsWith(".java")) return null;
        String base = sourceFile.substring(0, sourceFile.length() - 5);
        if (!JavaNames.isValidIdentifier(base) || JavaNames.isKeyword(base)) return null;
        Optional<String> source = workspace.bundledSource(cf.thisClass(), sourceFile);
        if (source.isEmpty()) return null;
        String pattern = "(?s)\\b(?:class|interface|enum|record)\\s+" + java.util.regex.Pattern.quote(base) + "\\b";
        if (!java.util.regex.Pattern.compile(pattern).matcher(source.get()).find()) return null;
        return new SemanticNameEngine.Candidate(base,100,"name recovered from attached source metadata");
    }

    private void planFields(Workspace workspace, HierarchyIndex hierarchy, ReflectionUsageAnalyzer.Result reflection,
                            boolean aggressive, MappingSet mappings) {
        for(ClassFile cf:hierarchy.classes().values()){
            List<SourceSymbolRecovery.FieldSymbol> sourceFields=sourceFields(workspace,cf);
            HashSet<String> used=new HashSet<>();
            for(MemberInfo f:cf.fields()) if(!isLikelyObfuscatedFieldName(f.name()))used.add(f.name());
            for(int fieldIndex=0;fieldIndex<cf.fields().size();fieldIndex++){
                MemberInfo f=cf.fields().get(fieldIndex);
                if(isSpecialField(f))continue;
                boolean suspicious=isLikelyObfuscatedFieldName(f.name());
                boolean rename=suspicious||(aggressive&&AccessFlags.has(f.accessFlags(),AccessFlags.PRIVATE));
                if(!rename)continue;
                if(!aggressive&&reflection.memberNames().contains(f.name()))continue;
                SemanticNameEngine.Candidate c=sourceFieldCandidate(cf,f,fieldIndex,sourceFields);
                if(c==null)c=semantics.fieldCandidate(cf,f);
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

    private void planMethods(Workspace workspace, HierarchyIndex hierarchy, ReflectionUsageAnalyzer.Result reflection,
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

            SemanticNameEngine.Candidate best=sourceMethodCandidate(workspace,hierarchy,group);
            if(best==null&&meaningfulExisting!=null){
                best=new SemanticNameEngine.Candidate(meaningfulExisting,98,"meaningful name retained elsewhere in override group");
            } else if(best==null) {
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


    private static List<SourceSymbolRecovery.FieldSymbol> sourceFields(Workspace workspace, ClassFile cf) {
        String sourceFile=DebugMetadata.sourceFile(cf);
        if(sourceFile==null)return List.of();
        Optional<String> source=workspace.bundledSource(cf.thisClass(),sourceFile);
        return source.map(SourceSymbolRecovery::fields).orElseGet(List::of);
    }

    private static SemanticNameEngine.Candidate sourceFieldCandidate(ClassFile cf,MemberInfo field,int index,List<SourceSymbolRecovery.FieldSymbol> sourceFields){
        if(sourceFields.size()!=cf.fields().size()||index<0||index>=sourceFields.size())return null;
        SourceSymbolRecovery.FieldSymbol symbol=sourceFields.get(index);
        if(!fieldTypeMatchesSource(field.descriptor(),symbol.type()))return null;
        return new SemanticNameEngine.Candidate(symbol.name(),100,"name recovered from attached source field order and type");
    }

    private static boolean fieldTypeMatchesSource(String descriptor,String sourceType){
        String d=descriptorSimpleType(descriptor);
        String s=sourceSimpleType(sourceType);
        return d.equals(s)||d.endsWith("."+s)||s.endsWith("."+d);
    }

    private static String descriptorSimpleType(String descriptor){
        int arrays=0;
        while(arrays<descriptor.length()&&descriptor.charAt(arrays)=='[')arrays++;
        String core=descriptor.substring(arrays);
        String base=switch(core.charAt(0)){
            case 'Z'->"boolean";case 'B'->"byte";case 'C'->"char";case 'S'->"short";case 'I'->"int";case 'J'->"long";case 'F'->"float";case 'D'->"double";
            case 'L'->{String x=core.substring(1,core.length()-1).replace('/','.');int p=x.lastIndexOf('.');yield p<0?x:x.substring(p+1);}
            default->core;
        };
        return base+"[]".repeat(arrays);
    }

    private static String sourceSimpleType(String sourceType){
        StringBuilder out=new StringBuilder();
        int angle=0;
        for(int i=0;i<sourceType.length();i++){
            char c=sourceType.charAt(i);
            if(c=='<'){angle++;continue;}
            if(c=='>'){angle=Math.max(0,angle-1);continue;}
            if(angle==0&&!Character.isWhitespace(c))out.append(c);
        }
        String x=out.toString().replace("...","[]");
        int arr=x.indexOf('[');
        String suffix=arr>=0?x.substring(arr):"";
        String core=arr>=0?x.substring(0,arr):x;
        int dot=core.lastIndexOf('.');
        if(dot>=0)core=core.substring(dot+1);
        return core+suffix;
    }

    private static SemanticNameEngine.Candidate sourceMethodCandidate(Workspace workspace, HierarchyIndex hierarchy, List<HierarchyIndex.MethodId> group) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (HierarchyIndex.MethodId id : group) {
            MemberInfo method = hierarchy.method(id);
            ClassFile cf = hierarchy.classFile(id.owner());
            if (method == null || cf == null || method.name().startsWith("<")) continue;
            int[] range = DebugMetadata.lineRange(cf, method);
            if (range[0] <= 0) continue;
            String sourceFile = DebugMetadata.sourceFile(cf);
            Optional<String> source = workspace.bundledSource(cf.thisClass(), sourceFile);
            if (source.isEmpty()) continue;
            DescriptorParser.MethodDescriptor md;
            try { md = DescriptorParser.method(method.descriptor()); } catch (RuntimeException ex) { continue; }
            String owner = simple(cf.thisClass());
            int dollar = owner.lastIndexOf('$');
            if (dollar >= 0 && dollar + 1 < owner.length()) owner = owner.substring(dollar + 1);
            SourceSymbolRecovery.MethodSymbols recovered = SourceSymbolRecovery.method(source.get(), range[0], range[1], md.parameterTypes().size(), false, owner);
            if (recovered != null && JavaNames.isValidIdentifier(recovered.name()) && !JavaNames.isKeyword(recovered.name())) names.add(recovered.name());
        }
        if (names.size() == 1) return new SemanticNameEngine.Candidate(names.iterator().next(),100,"name recovered from attached source and line metadata");
        return null;
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
