package dev.aegis.decompile;

import dev.aegis.classfile.*;
import dev.aegis.rename.MappingSet;
import dev.aegis.rename.SemanticNameEngine;
import dev.aegis.util.JavaNames;
import java.util.*;

public final class SourceDecompiler {
    public String decompile(byte[] bytes, MappingSet mappings) { return decompile(bytes, mappings, null); }

    public String decompile(byte[] bytes, MappingSet mappings, String originalSource) {
        ClassFile cf = ClassFileParser.parse(bytes);
        MappingSet map = mappings == null ? new MappingSet() : mappings;
        String mappedInternal = map.className(cf.thisClass());
        int slash = mappedInternal.lastIndexOf('/');
        String pkg = slash < 0 ? "" : mappedInternal.substring(0, slash).replace('/', '.');
        String simple = slash < 0 ? mappedInternal : mappedInternal.substring(slash + 1);
        simple = JavaNames.sanitize(simple.replace('$', '_'), "DecompiledClass");

        StringBuilder out = new StringBuilder();
        out.append("// Decompiled by Aegis - made by hcrdso\n");
        out.append("// Classfile ").append(cf.majorVersion()).append('.').append(cf.minorVersion())
                .append(" / Java ").append(cf.javaVersion()).append("\n");
        String sourceFile = DebugMetadata.sourceFile(cf);
        if (sourceFile != null) out.append("// [recovered metadata] SourceFile: ").append(sourceFile).append("\n");
        List<String> smapFiles = DebugMetadata.smapSourceFiles(cf);
        if (!smapFiles.isEmpty()) out.append("// [recovered metadata] SMAP sources: ").append(String.join(", ", smapFiles)).append("\n");
        String classSig = DebugMetadata.genericSignature(cf);
        if (classSig != null) out.append("// [recovered metadata] generic signature: ").append(classSig).append("\n");
        out.append('\n');
        if (!pkg.isEmpty()) out.append("package ").append(pkg).append(";\n\n");

        if (originalSource != null) {
            String originalSimple = cf.thisClass(); int os = originalSimple.lastIndexOf('/'); if (os >= 0) originalSimple = originalSimple.substring(os + 1);
            for (OriginalCommentRecovery.SourceComment c : OriginalCommentRecovery.forType(originalSource, originalSimple))
                out.append(OriginalCommentRecovery.asTaggedLine(c)).append("\n");
        }
        List<RecordComponents.Component> recordComponents = RecordComponents.from(cf);
        boolean recordType = !recordComponents.isEmpty() && "java/lang/Record".equals(cf.superClass());
        List<String> permitted = ModernClassFeatures.permittedSubclasses(cf);
        boolean sealedType = !permitted.isEmpty();
        String mods = AccessFlags.classModifiers(cf.accessFlags());
        if (recordType) mods = mods.replace("final", "").replaceAll("\\s+", " ").trim();
        if (!mods.isEmpty()) out.append(mods).append(' ');
        if (sealedType) out.append("sealed ");
        boolean annotation = AccessFlags.has(cf.accessFlags(), AccessFlags.ANNOTATION);
        boolean iface = AccessFlags.has(cf.accessFlags(), AccessFlags.INTERFACE);
        boolean enm = AccessFlags.has(cf.accessFlags(), AccessFlags.ENUM);
        if (recordType) out.append("record ");
        else if (annotation) out.append("@interface ");
        else if (enm) out.append("enum ");
        else if (iface) out.append("interface ");
        else out.append("class ");
        out.append(simple);
        GenericSignatureParser.ClassSig parsedClassSig = GenericSignatureParser.clazz(classSig, map);
        if (parsedClassSig != null && !parsedClassSig.typeParameters().isEmpty()) out.append(parsedClassSig.typeParameters());
        if (recordType) {
            out.append('(');
            for (int i=0;i<recordComponents.size();i++) {
                if(i>0)out.append(", "); RecordComponents.Component c=recordComponents.get(i);
                String type=GenericSignatureParser.fieldType(c.genericSignature(),map);
                if(type==null)try{type=map.mapJavaType(DescriptorParser.fieldType(c.descriptor()));}catch(RuntimeException ex){type="Object";}
                out.append(type).append(' ').append(JavaNames.sanitize(map.fieldName(cf.thisClass(),c.name(),c.descriptor()),"component"+i));
            }
            out.append(')');
        }

        String renderedSuper = parsedClassSig != null ? parsedClassSig.superType() : (cf.superClass()==null?null:javaClass(cf.superClass(),map));
        List<String> renderedInterfaces = parsedClassSig != null ? parsedClassSig.interfaces() : cf.interfaces().stream().map(x -> javaClass(x,map)).toList();
        if (!recordType && !iface && !enm && renderedSuper != null && !renderedSuper.equals("java.lang.Object")) out.append(" extends ").append(renderedSuper);
        if (!renderedInterfaces.isEmpty()) {
            out.append(iface ? " extends " : " implements ");
            out.append(String.join(", ", renderedInterfaces));
        }
        if (!permitted.isEmpty()) out.append(" permits ").append(String.join(", ", permitted.stream().map(x -> javaClass(x,map)).toList()));
        out.append(" {\n");

        Set<String> recordFields=new HashSet<>(); for(RecordComponents.Component c:recordComponents)recordFields.add(c.name()+"\u0000"+c.descriptor());
        for (MemberInfo field : cf.fields()) if(!recordFields.contains(field.name()+"\u0000"+field.descriptor())) emitField(out, cf, field, map);
        if (!cf.fields().isEmpty() && !cf.methods().isEmpty()) out.append('\n');
        for (MemberInfo method : cf.methods()) {
            if (recordType && isImplicitRecordMethod(cf, method, recordComponents)) continue;
            emitMethod(out, cf, method, map, simple, iface, originalSource);
        }
        out.append("}\n");
        return out.toString();
    }

    private static boolean isImplicitRecordMethod(ClassFile cf, MemberInfo m, List<RecordComponents.Component> components) {
        if (m.name().equals("<clinit>")) return false;
        try {
            if (m.name().equals("<init>")) return isTrivialRecordConstructor(cf,m,components);
            for (RecordComponents.Component c:components) if (m.name().equals(c.name()) && m.descriptor().equals("()"+c.descriptor())) {
                AttributeInfo ca=m.attribute("Code"); if(ca==null)return false;
                List<Instruction> xs=BytecodeDecoder.decode(CodeAttribute.parse(ca,cf.constantPool()).code()).stream().filter(x->x.opcode()!=0).toList();
                if(xs.size()==3&&xs.get(0).opcode()==42&&xs.get(1).opcode()==180&&xs.get(2).opcode()>=172&&xs.get(2).opcode()<=176){
                    ConstantPool.MemberRef r=cf.constantPool().memberRef(xs.get(1).u2(0));
                    if(r.owner().equals(cf.thisClass())&&r.name().equals(c.name())&&r.descriptor().equals(c.descriptor()))return true;
                }
            }
            if (Set.of("equals","hashCode","toString").contains(m.name()) && usesObjectMethodsBootstrap(cf,m)) return true;
        } catch(RuntimeException ignored) { }
        return false;
    }

    private static boolean isTrivialRecordConstructor(ClassFile cf,MemberInfo m,List<RecordComponents.Component> components){
        AttributeInfo ca=m.attribute("Code");if(ca==null)return false;
        DescriptorParser.MethodDescriptor md=DescriptorParser.method(m.descriptor());
        if(md.parameterTypes().size()!=components.size())return false;
        for(int i=0;i<components.size();i++)if(!md.parameterTypes().get(i).equals(DescriptorParser.fieldType(components.get(i).descriptor())))return false;
        List<Instruction> xs=BytecodeDecoder.decode(CodeAttribute.parse(ca,cf.constantPool()).code()).stream().filter(x->x.opcode()!=0).toList();
        int pos=0;if(xs.size()<3||xs.get(pos++).opcode()!=42)return false;
        Instruction superCall=xs.get(pos++);if(superCall.opcode()!=183)return false;
        ConstantPool.MemberRef sr=cf.constantPool().memberRef(superCall.u2(0));if(!sr.owner().equals("java/lang/Record")||!sr.name().equals("<init>"))return false;
        int slot=1;
        for(RecordComponents.Component c:components){
            if(pos+2>=xs.size()||xs.get(pos++).opcode()!=42)return false;
            Instruction load=xs.get(pos++);Integer loaded=localLoadSlot(load);if(loaded==null||loaded!=slot)return false;
            Instruction put=xs.get(pos++);if(put.opcode()!=181)return false;
            ConstantPool.MemberRef fr=cf.constantPool().memberRef(put.u2(0));if(!fr.owner().equals(cf.thisClass())||!fr.name().equals(c.name())||!fr.descriptor().equals(c.descriptor()))return false;
            String jt=DescriptorParser.fieldType(c.descriptor());slot+=(jt.equals("long")||jt.equals("double"))?2:1;
        }
        return pos==xs.size()-1&&xs.get(pos).opcode()==177;
    }

    private static Integer localLoadSlot(Instruction i){int op=i.opcode();if(op>=21&&op<=25)return i.u1(0);if(op>=26&&op<=45)return switch(op){case 26,30,34,38,42->0;case 27,31,35,39,43->1;case 28,32,36,40,44->2;default->3;};return null;}

    private static boolean usesObjectMethodsBootstrap(ClassFile cf,MemberInfo m){
        AttributeInfo ca=m.attribute("Code");if(ca==null)return false;BootstrapMethods bs=BootstrapMethods.from(cf);
        for(Instruction i:BytecodeDecoder.decode(CodeAttribute.parse(ca,cf.constantPool()).code()))if(i.opcode()==186){
            ConstantPool.DynamicRef d=cf.constantPool().dynamicRef(i.u2(0));BootstrapMethods.BootstrapMethod bm=bs.get(d.bootstrapMethodIndex());if(bm==null)continue;
            ConstantPool.Entry e=cf.constantPool().entry(bm.methodHandleIndex());if(e instanceof ConstantPool.MethodHandleEntry h){
                ConstantPool.MemberRef r=cf.constantPool().memberRef(h.referenceIndex());if(r.owner().equals("java/lang/runtime/ObjectMethods")&&r.name().equals("bootstrap"))return true;
            }
        }
        return false;
    }

    private static void emitField(StringBuilder out, ClassFile cf, MemberInfo f, MappingSet map) {
        MappingSet.Decision rename = map.fieldDecision(cf.thisClass(), f.name(), f.descriptor());
        if (rename != null) out.append("    // [Aegis rename ").append(rename.confidence()).append("%] ").append(f.name()).append(" -> ").append(rename.target()).append(": ").append(rename.reason()).append("\n");
        String sig = DebugMetadata.genericSignature(f, cf.constantPool());
        if (sig != null) out.append("    // [recovered metadata] generic signature: ").append(sig).append("\n");
        out.append("    ");
        String mods = AccessFlags.fieldModifiers(f.accessFlags());
        if (!mods.isEmpty()) out.append(mods).append(' ');
        String type = GenericSignatureParser.fieldType(sig, map);
        if (type == null) {
            try { type = map.mapJavaType(DescriptorParser.fieldType(f.descriptor())); }
            catch (RuntimeException ex) { type = "Object /* " + f.descriptor() + " */"; }
        }
        out.append(type).append(' ')
                .append(JavaNames.sanitize(map.fieldName(cf.thisClass(), f.name(), f.descriptor()), "field"));
        String constant = constantValue(cf, f);
        if (constant != null) out.append(" = ").append(constant);
        out.append(";\n");
    }

    private static String constantValue(ClassFile cf, MemberInfo f) {
        AttributeInfo a = f.attribute("ConstantValue");
        if (a == null || a.data().length != 2) return null;
        try {
            int idx = a.reader().u2();
            Object v = cf.constantPool().constant(idx);
            if (v instanceof String s) return JavaNames.escapeString(s);
            if (v instanceof Character c) return "'" + c + "'";
            if (v instanceof Long l) return l + "L";
            if (v instanceof Float x) return x + "f";
            if (v instanceof Double x) return x + "d";
            if (v instanceof Integer x) {
                return switch (f.descriptor()) {
                    case "Z" -> x == 0 ? "false" : "true";
                    case "C" -> "(char)" + x;
                    default -> x.toString();
                };
            }
            return String.valueOf(v);
        } catch (RuntimeException ex) { return null; }
    }

    private static void emitMethod(StringBuilder out, ClassFile cf, MemberInfo m, MappingSet map,
                                   String currentSimpleName, boolean ownerIsInterface, String originalSource) {
        if (!m.name().startsWith("<")) {
            MappingSet.Decision rename = map.methodDecision(cf.thisClass(), m.name(), m.descriptor());
            if (rename != null) out.append("    // [Aegis rename ").append(rename.confidence()).append("%] ").append(m.name()).append(" -> ").append(rename.target()).append(": ").append(rename.reason()).append("\n");
        }
        int[] sourceLines = DebugMetadata.lineRange(cf, m);
        if (sourceLines[0] > 0 && originalSource != null) {
            for (OriginalCommentRecovery.SourceComment c : OriginalCommentRecovery.forMethod(originalSource, sourceLines[0], sourceLines[1]))
                out.append("    ").append(OriginalCommentRecovery.asTaggedLine(c)).append("\n");
        }
        if (sourceLines[0] > 0) {
            out.append("    // [recovered metadata] source line");
            if (sourceLines[0] == sourceLines[1]) out.append(' ').append(sourceLines[0]);
            else out.append("s ").append(sourceLines[0]).append('-').append(sourceLines[1]);
            out.append("\n");
        }
        String genericSig = DebugMetadata.genericSignature(m, cf.constantPool());
        if (genericSig != null) out.append("    // [recovered metadata] generic signature: ").append(genericSig).append("\n");
        for (RecoveredCommentEngine.Comment c : new RecoveredCommentEngine().infer(cf, m, map)) {
            out.append("    // [Aegis inferred comment ").append(c.confidence()).append("%] ").append(c.text()).append("\n");
        }
        if (m.name().equals("<clinit>")) {
            out.append("    static {\n");
            emitBody(out, cf, m, map, 2, "void", List.of(), true);
            out.append("    }\n\n");
            return;
        }

        DescriptorParser.MethodDescriptor md;
        try { md = DescriptorParser.method(m.descriptor()); }
        catch (RuntimeException ex) {
            out.append("    // Invalid descriptor: ").append(m.name()).append(m.descriptor()).append("\n\n");
            return;
        }

        GenericSignatureParser.MethodSig parsedMethodSig = GenericSignatureParser.method(genericSig, map);
        out.append("    ");
        String mods = AccessFlags.methodModifiers(m.accessFlags());
        if (!mods.isEmpty()) out.append(mods).append(' ');
        if (ownerIsInterface && !AccessFlags.has(m.accessFlags(), AccessFlags.ABSTRACT)
                && !AccessFlags.has(m.accessFlags(), AccessFlags.STATIC)
                && !AccessFlags.has(m.accessFlags(), AccessFlags.PRIVATE)) out.append("default ");

        boolean constructor = m.name().equals("<init>");
        String returnType = parsedMethodSig != null ? parsedMethodSig.returnType() : map.mapJavaType(md.returnType());
        if (!constructor && parsedMethodSig != null && !parsedMethodSig.typeParameters().isEmpty()) out.append(parsedMethodSig.typeParameters()).append(' ');
        if (constructor) out.append(currentSimpleName);
        else out.append(returnType).append(' ')
                .append(JavaNames.sanitize(map.methodName(cf.thisClass(), m.name(), m.descriptor()), "method"));
        out.append('(');

        List<String> paramNames = new ArrayList<>();
        Set<String> usedParamNames = new HashSet<>();
        for (int i = 0; i < md.parameterTypes().size(); i++) {
            if (i > 0) out.append(", ");
            String type = parsedMethodSig != null && parsedMethodSig.parameterTypes().size()==md.parameterTypes().size()
                    ? parsedMethodSig.parameterTypes().get(i) : map.mapJavaType(md.parameterTypes().get(i));
            if (AccessFlags.has(m.accessFlags(), AccessFlags.VARARGS) && i == md.parameterTypes().size() - 1 && type.endsWith("[]")) {
                type = type.substring(0, type.length() - 2) + "...";
            }
            String p = parameterName(cf, m, md, i, type, map);
            String base = p; int suffix = 2; while (usedParamNames.contains(p)) p = base + suffix++;
            usedParamNames.add(p);
            paramNames.add(p);
            out.append(type).append(' ').append(p);
        }
        out.append(')');

        List<String> exceptions = parsedMethodSig != null && !parsedMethodSig.throwsTypes().isEmpty() ? parsedMethodSig.throwsTypes() : exceptions(cf, m, map);
        if (!exceptions.isEmpty()) out.append(" throws ").append(String.join(", ", exceptions));

        boolean noCode = AccessFlags.has(m.accessFlags(), AccessFlags.ABSTRACT) || AccessFlags.has(m.accessFlags(), AccessFlags.NATIVE) || m.attribute("Code") == null;
        if (noCode) { out.append(";\n\n"); return; }
        out.append(" {\n");
        emitBody(out, cf, m, map, 2, returnType, paramNames, false);
        out.append("    }\n\n");
    }

    private static void emitBody(StringBuilder out, ClassFile cf, MemberInfo m, MappingSet map, int indent,
                                 String returnType, List<String> params, boolean staticInitializer) {
        AttributeInfo a = m.attribute("Code");
        if (a == null) return;
        try {
            CodeAttribute code = CodeAttribute.parse(a, cf.constantPool());
            MethodBodyDecompiler.Result result = MethodBodyDecompiler.decompile(cf, m, code, map, params);
            for (String line : result.lines()) out.append("    ".repeat(indent)).append(line).append('\n');
            if (!result.complete() && !staticInitializer) {
                if (!returnType.equals("void") && !result.hasTerminalReturn()) {
                    out.append("    ".repeat(indent)).append("return ").append(DescriptorParser.defaultValue(returnType)).append("; // Aegis fallback\n");
                }
            }
        } catch (RuntimeException ex) {
            out.append("    ".repeat(indent)).append("// Aegis parser error: ").append(safe(ex.getMessage())).append('\n');
            if (!returnType.equals("void") && !staticInitializer)
                out.append("    ".repeat(indent)).append("return ").append(DescriptorParser.defaultValue(returnType)).append(";\n");
        }
    }

    private static String parameterName(ClassFile cf, MemberInfo m, DescriptorParser.MethodDescriptor md, int parameterIndex, String javaType, MappingSet map) {
        String fallback = SemanticNameEngine.parameterBase(javaType, parameterIndex);
        String mappedMethod = m.name().startsWith("<") ? m.name() : map.methodName(cf.thisClass(), m.name(), m.descriptor());
        String usageName = semanticParameterFromBytecode(cf, m, md, parameterIndex, map);
        if (usageName != null) fallback = usageName;
        else if (md.parameterTypes().size()==1 && !m.name().startsWith("<")) {
            String semantic = semanticParameterFromMethod(mappedMethod, javaType);
            if (semantic != null) fallback = semantic;
        }
        MethodParameters mp = MethodParameters.from(m, cf.constantPool());
        MethodParameters.Parameter p = mp.get(parameterIndex);
        if (p != null && p.name() != null && !p.name().isBlank()) return JavaNames.sanitize(p.name(), fallback);
        AttributeInfo ca = m.attribute("Code");
        if (ca == null) return JavaNames.sanitize(fallback, "param" + parameterIndex);
        try {
            CodeAttribute code = CodeAttribute.parse(ca, cf.constantPool());
            LocalVariableTable table = LocalVariableTable.from(code, cf.constantPool());
            int slot = AccessFlags.has(m.accessFlags(), AccessFlags.STATIC) ? 0 : 1;
            for (int i = 0; i < parameterIndex; i++) slot += md.slotWidths().get(i);
            LocalVariableTable.Local lv = table.find(slot, 0);
            if (lv != null && !"this".equals(lv.name())) return JavaNames.sanitize(lv.name(), fallback);
        } catch (RuntimeException ignored) { }
        return JavaNames.sanitize(fallback, "param" + parameterIndex);
    }

    private static String semanticParameterFromBytecode(ClassFile cf, MemberInfo m, DescriptorParser.MethodDescriptor md,
                                                        int parameterIndex, MappingSet map) {
        AttributeInfo ca=m.attribute("Code"); if(ca==null)return null;
        int slot=AccessFlags.has(m.accessFlags(),AccessFlags.STATIC)?0:1;
        for(int i=0;i<parameterIndex;i++)slot+=md.slotWidths().get(i);
        try {
            CodeAttribute code=CodeAttribute.parse(ca,cf.constantPool());
            List<Instruction> xs=BytecodeDecoder.decode(code.code());
            for(int i=0;i<xs.size();i++){
                Instruction in=xs.get(i); Integer loaded=loadedLocal(in);
                if(loaded==null||loaded!=slot)continue;
                // Constructor/setter pattern: aload_0; <parameter>; putfield owner.field
                for(int j=i+1;j<Math.min(xs.size(),i+4);j++){
                    Instruction next=xs.get(j);
                    if(next.opcode()==181){
                        ConstantPool.MemberRef r=cf.constantPool().memberRef(next.u2(0));
                        if(r.owner().equals(cf.thisClass()))return JavaNames.sanitize(map.fieldName(r.owner(),r.name(),r.descriptor()),"param"+parameterIndex);
                    }
                    if(isControlBoundary(next.opcode()))break;
                }
                // Direct argument to a meaningful one-argument API, e.g. setPath(path).
                if(i+1<xs.size()){
                    Instruction next=xs.get(i+1); int op=next.opcode();
                    if(op>=182&&op<=185){
                        ConstantPool.MemberRef r=cf.constantPool().memberRef(next.u2(0));
                        DescriptorParser.MethodDescriptor called=DescriptorParser.method(r.descriptor());
                        if(called.parameterTypes().size()==1 && JavaNames.isValidIdentifier(r.name()) && !JavaNames.isLikelyObfuscated(r.name())){
                            String candidate=semanticParameterFromMethod(r.name(),md.parameterTypes().get(parameterIndex));
                            if(candidate!=null)return JavaNames.sanitize(candidate,"param"+parameterIndex);
                        }
                    }
                }
            }
        } catch(RuntimeException ignored) { }
        return null;
    }

    private static Integer loadedLocal(Instruction i){
        int op=i.opcode();
        if(op>=21&&op<=25)return i.u1(0);
        if(op>=26&&op<=45)return switch(op){case 26,30,34,38,42->0;case 27,31,35,39,43->1;case 28,32,36,40,44->2;default->3;};
        return null;
    }

    private static boolean isControlBoundary(int op){return (op>=153&&op<=171)||op==167||op==200||op>=172&&op<=177||op==191;}

    private static String semanticParameterFromMethod(String methodName,String javaType) {
        if(methodName==null||methodName.isBlank())return null;
        String[] prefixes={"set","load","save","register","release","remove","add","find","parse","open","close","update","render","write","read","decode","encode","handle","process"};
        for(String pre:prefixes)if(methodName.startsWith(pre)&&methodName.length()>pre.length()){
            String noun=methodName.substring(pre.length());
            String candidate=Character.toLowerCase(noun.charAt(0))+noun.substring(1);
            if(candidate.equals("id")||candidate.equals("identifier"))return "id";
            if(candidate.endsWith("s")&&javaType.endsWith("[]"))return candidate;
            return candidate;
        }
        if(methodName.startsWith("get")&&methodName.length()>3)return javaType.equals("int")?"index":"key";
        return null;
    }

    private static List<String> exceptions(ClassFile cf, MemberInfo m, MappingSet map) {
        AttributeInfo a = m.attribute("Exceptions");
        if (a == null) return List.of();
        try {
            ByteReader r = a.reader();
            int n = r.u2();
            ArrayList<String> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) out.add(javaClass(cf.constantPool().className(r.u2()), map));
            return out;
        } catch (RuntimeException ex) { return List.of(); }
    }

    static String javaClass(String internal, MappingSet map) {
        if (internal == null) return "java.lang.Object";
        if (internal.startsWith("[")) return map.mapJavaType(DescriptorParser.fieldType(internal));
        return map.className(internal).replace('/', '.').replace('$', '.');
    }

    private static String safe(String s) {
        if (s == null) return "unknown";
        return s.replace("\n", " ").replace("\r", " ");
    }
}
