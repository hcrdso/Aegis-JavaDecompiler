package dev.aegis.rename;

import dev.aegis.classfile.*;
import dev.aegis.util.JavaNames;
import java.util.*;
import java.util.regex.*;

/** Static semantic heuristics used when original identifiers were destroyed. */
public final class SemanticNameEngine {
    public record Candidate(String name, int confidence, String reason) {}

    private static final Set<String> BORING_CALLS = Set.of(
            "<init>","valueOf","requireNonNull","hashCode","equals","toString","iterator","hasNext","next",
            "print","println","printf","error","warn","info","debug","trace","log");
    private static final Set<String> VERBS = Set.of(
            "get","set","load","save","read","write","create","open","close","register","release","remove","add","find","parse","format","update","render","draw","tick","reload","dump","apply","build","check","validate","compute","resolve","encode","decode","encrypt","decrypt","connect","send","receive","handle","process","run","test","compare","copy","move","delete","upload","download");
    private static final Pattern WORD = Pattern.compile("[A-Za-z][A-Za-z0-9]{1,30}");

    public Candidate fieldCandidate(ClassFile cf, MemberInfo f) {
        String t;
        try { t=DescriptorParser.fieldType(f.descriptor()); } catch(RuntimeException ex){ return new Candidate("value",35,"unknown field type"); }
        String simple=simpleJavaType(t);
        Candidate generic=genericFieldCandidate(cf,f,simple);
        if(generic!=null)return generic;
        boolean sf=AccessFlags.has(f.accessFlags(),AccessFlags.STATIC)&&AccessFlags.has(f.accessFlags(),AccessFlags.FINAL);
        if(simple.equals("Logger")) return new Candidate(sf?"LOGGER":"logger",98,"logger field type");
        if(simple.equals("ResourceManager")) return new Candidate("resourceManager",95,"field type ResourceManager");
        if(simple.equals("Executor")||simple.endsWith("Executor")) return new Candidate(decap(simple),88,"executor field type");
        if(simple.equals("Path")) return new Candidate("path",90,"field type Path");
        if(simple.equals("String")) return new Candidate(sf?"TEXT":"text",68,"String field type");
        if(simple.equals("Map")||simple.endsWith("Map")) return new Candidate("byKey",72,"map-like field type");
        if(simple.equals("Set")||simple.endsWith("Set")) return new Candidate("values",70,"set-like field type");
        if(simple.equals("List")||simple.endsWith("List")||simple.equals("Collection")) return new Candidate("values",70,"collection field type");
        if(simple.endsWith("Cache")) return new Candidate("cache",84,"cache-like field type");
        if(simple.endsWith("Manager")) return new Candidate(decap(simple),84,"manager-like field type");
        if(t.equals("boolean")) return new Candidate(sf?"ENABLED":"enabled",58,"boolean field");
        if(t.equals("int")) return new Candidate(sf?"COUNT":"count",52,"integer field");
        if(t.equals("long")) return new Candidate(sf?"VALUE":"value",48,"long field");
        if(t.endsWith("[]")) return new Candidate("values",58,"array field");
        String base=decap(simple);
        return new Candidate(base.isBlank()?"value":base,62,"field type "+simple);
    }

    public Candidate methodCandidate(ClassFile cf, MemberInfo m, MappingSet mappings) {
        if(m.name().startsWith("lambda$")) {
            String body=m.name().substring("lambda$".length());
            String[] p=body.split("\\$"); String owner=p.length>0?p[0]:"Body"; String id=p.length>1?p[p.length-1]:"0";
            return new Candidate("lambdaBody"+capitalize(JavaNames.sanitize(owner,"Body"))+id,98,"compiler lambda helper renamed to avoid javac synthetic-name collision");
        }
        if(m.name().startsWith("access$"))return new Candidate("syntheticAccess"+m.name().substring("access$".length()),94,"compiler synthetic access bridge");
        Candidate a=accessorCandidate(cf,m,mappings); if(a!=null)return a;
        AttributeInfo ca=m.attribute("Code");
        DescriptorParser.MethodDescriptor md;
        try{md=DescriptorParser.method(m.descriptor());}catch(RuntimeException ex){return new Candidate("method",20,"invalid descriptor");}
        if(ca!=null){
            try{
                CodeAttribute code=CodeAttribute.parse(ca,cf.constantPool());
                List<Instruction> xs=BytecodeDecoder.decode(code.code());
                Candidate delegate=delegateCandidate(cf,m,xs); if(delegate!=null)return delegate;
                Candidate strings=stringCandidate(cf,xs); if(strings!=null)return strings;
                Candidate shape=shapeCandidate(cf,m,md,xs); if(shape!=null)return shape;
            }catch(RuntimeException ignored){}
        }
        String ret=md.returnType();
        if("boolean".equals(ret))return new Candidate("isValid",46,"boolean-returning method");
        if("void".equals(ret)){
            if(md.parameterTypes().isEmpty())return new Candidate("runAction",36,"void no-arg method");
            return new Candidate("process",34,"void method");
        }
        String simple=simpleJavaType(ret);
        if(simple.equals("String"))return new Candidate("getText",44,"returns String");
        if(simple.equals("Iterator"))return new Candidate("iterator",80,"returns Iterator");
        if(simple.equals("CompletableFuture"))return new Candidate("schedule",50,"returns CompletableFuture");
        return new Candidate("get"+capitalize(simple),38,"return type "+simple);
    }

    private Candidate accessorCandidate(ClassFile cf, MemberInfo m, MappingSet mappings){
        AttributeInfo ca=m.attribute("Code");if(ca==null)return null;
        try{
            CodeAttribute code=CodeAttribute.parse(ca,cf.constantPool());
            List<Instruction> xs=BytecodeDecoder.decode(code.code()).stream().filter(i->i.opcode()!=0).toList();
            if(xs.size()==3&&xs.get(0).opcode()==42&&xs.get(1).opcode()==180&&isReturn(xs.get(2).opcode())){
                ConstantPool.MemberRef r=cf.constantPool().memberRef(xs.get(1).u2(0));
                if(r.owner().equals(cf.thisClass())){
                    String f=mappings.fieldName(r.owner(),r.name(),r.descriptor());
                    return new Candidate(("Z".equals(r.descriptor())?"is":"get")+capitalize(f),96,"direct field getter");
                }
            }
            if(xs.size()==4&&xs.get(0).opcode()==42&&isParameterLoad(xs.get(1))&&xs.get(2).opcode()==181&&xs.get(3).opcode()==177){
                ConstantPool.MemberRef r=cf.constantPool().memberRef(xs.get(2).u2(0));
                if(r.owner().equals(cf.thisClass()))return new Candidate("set"+capitalize(mappings.fieldName(r.owner(),r.name(),r.descriptor())),96,"direct field setter");
            }
        }catch(RuntimeException ignored){}
        return null;
    }

    private Candidate delegateCandidate(ClassFile cf,MemberInfo m,List<Instruction> xs){
        if(xs.size()>18)return null;
        LinkedHashMap<String,Integer> calls=new LinkedHashMap<>();
        int invokeCount=0;
        for(Instruction i:xs){
            int op=i.opcode();
            if((op>=153&&op<=171)||(op>=96&&op<=152)||op==191||op==194||op==195||op==179||op==181)return null;
            if(op>=182&&op<=185){
                invokeCount++;
                try{ConstantPool.MemberRef r=cf.constantPool().memberRef(i.u2(0));if(!BORING_CALLS.contains(r.name())&&JavaNames.isValidIdentifier(r.name())&&!JavaNames.isLikelyObfuscated(r.name()))calls.merge(r.name(),1,Integer::sum);}catch(RuntimeException ignored){}
            }
        }
        if(invokeCount==1&&calls.size()==1){String n=calls.keySet().iterator().next();return new Candidate(n,88,"pure thin wrapper/delegation to "+n);}
        return null;
    }

    private Candidate stringCandidate(ClassFile cf,List<Instruction> xs){
        Candidate best=null;
        for(Instruction i:xs){
            if(i.opcode()!=18&&i.opcode()!=19&&i.opcode()!=20)continue;
            try{
                Object v=cf.constantPool().constant(i.opcode()==18?i.u1(0):i.u2(0));if(!(v instanceof String s)||s.length()<4)continue;
                ArrayList<String> words=new ArrayList<>();Matcher m=WORD.matcher(s);while(m.find())words.add(m.group().toLowerCase(Locale.ROOT));
                for(int p=0;p<words.size();p++){
                    String w=words.get(p);if(!VERBS.contains(w))continue;
                    String noun=null;for(int q=p+1;q<Math.min(words.size(),p+5);q++){String x=words.get(q);if(!Set.of("to","the","a","an","into","from","for","of","with","failed","unable","error").contains(x)){noun=x;break;}}
                    String n=w+(noun==null?"":capitalize(noun));
                    Candidate c=new Candidate(n,74,"semantic string literal: \""+shorten(s)+"\"");
                    if(best==null||c.confidence()>best.confidence())best=c;
                }
            }catch(RuntimeException ignored){}
        }
        return best;
    }

    private Candidate shapeCandidate(ClassFile cf,MemberInfo m,DescriptorParser.MethodDescriptor md,List<Instruction> xs){
        boolean createsOwner=false, throwsEx=false, monitor=false;int fieldReads=0,fieldWrites=0,arith=0,branches=0,calls=0;
        for(Instruction i:xs){
            try{
                int op=i.opcode();
                if(op==187&&cf.constantPool().className(i.u2(0)).equals(cf.thisClass()))createsOwner=true;
                if(op==191)throwsEx=true;if(op==194||op==195)monitor=true;
                if(op==178||op==180)fieldReads++;if(op==179||op==181)fieldWrites++;
                if((op>=96&&op<=131)||op==132)arith++;
                if((op>=153&&op<=171)||op==167||op==200)branches++;
                if(op>=182&&op<=186)calls++;
            }catch(RuntimeException ignored){}
        }
        if(AccessFlags.has(m.accessFlags(),AccessFlags.STATIC)&&createsOwner&&md.returnType().replace('.','/').equals(cf.thisClass()))return new Candidate("create",82,"static factory shape");
        if(throwsEx&&"void".equals(md.returnType()))return new Candidate("validate",52,"guard/throwing method shape");
        if(monitor)return new Candidate("withLock",48,"monitor/synchronization shape");
        if(fieldWrites>0&&fieldReads==0&&"void".equals(md.returnType()))return new Candidate("updateState",48,"field-writing method");
        if(calls==0&&fieldReads==0&&fieldWrites==0&&arith>0&&md.parameterTypes().size()==1&&md.returnType().equals(md.parameterTypes().get(0)))
            return new Candidate("transform"+capitalize(simpleJavaType(md.returnType())),64,"pure arithmetic transform shape");
        if(calls==0&&fieldReads==0&&fieldWrites==0&&branches>0&&"boolean".equals(md.returnType()))return new Candidate("test",58,"pure predicate shape");
        return null;
    }

    public Candidate classCandidate(ClassFile cf,int ordinal){
        String roleNoun=dominantSemanticNoun(cf);
        String role=classRole(cf);
        if(roleNoun!=null&&role!=null)return new Candidate(capitalize(roleNoun)+role,84,"whole-class semantic profile: "+roleNoun+" + "+role.toLowerCase(Locale.ROOT));
        String sup=cf.superClass();
        if(sup!=null){String st=simpleInternal(sup);if(st.endsWith("Exception"))return new Candidate("RecoveredException"+ordinal,72,"extends "+st);if(st.endsWith("Error"))return new Candidate("RecoveredError"+ordinal,72,"extends "+st);}
        for(String i:cf.interfaces()){
            String it=simpleInternal(i);
            if(it.equals("Runnable"))return new Candidate((roleNoun==null?"Task":capitalize(roleNoun)+"Task")+ordinal,72,"implements Runnable");
            if(it.equals("Comparator"))return new Candidate((roleNoun==null?"Comparator":capitalize(roleNoun)+"Comparator")+ordinal,76,"implements Comparator");
            if(it.endsWith("Listener"))return new Candidate((roleNoun==null?"Recovered":capitalize(roleNoun))+"Listener"+ordinal,76,"implements "+it);
            if(it.equals("AutoCloseable")&&roleNoun!=null)return new Candidate(capitalize(roleNoun)+"Manager",70,"AutoCloseable semantic resource owner");
        }
        if(AccessFlags.has(cf.accessFlags(),AccessFlags.INTERFACE))return new Candidate("RecoveredInterface"+String.format("%03d",ordinal),55,"interface type");
        if(AccessFlags.has(cf.accessFlags(),AccessFlags.ENUM))return new Candidate("RecoveredEnum"+String.format("%03d",ordinal),60,"enum type");
        if(AccessFlags.has(cf.accessFlags(),AccessFlags.ANNOTATION))return new Candidate("RecoveredAnnotation"+String.format("%03d",ordinal),60,"annotation type");
        if(roleNoun!=null)return new Candidate(capitalize(roleNoun)+"Component"+ordinal,66,"dominant whole-class semantic noun: "+roleNoun);
        return new Candidate("RecoveredClass"+String.format("%03d",ordinal),42,"no reliable semantic class clue");
    }

    private Candidate genericFieldCandidate(ClassFile cf,MemberInfo f,String outerSimple){
        String sig=DebugMetadata.genericSignature(f,cf.constantPool());
        if(sig==null)return null;
        Matcher m=Pattern.compile("L([^;<]+)").matcher(sig);ArrayList<String> types=new ArrayList<>();
        while(m.find())types.add(simpleInternal(m.group(1)));
        if(types.size()<2)return null;
        if((outerSimple.equals("Set")||outerSimple.endsWith("Set")||outerSimple.equals("List")||outerSimple.endsWith("List")||outerSimple.equals("Collection"))){
            String elem=semanticTypeNoun(types.get(1));
            return new Candidate(pluralize(elem),88,"generic collection element type "+types.get(1));
        }
        if((outerSimple.equals("Map")||outerSimple.endsWith("Map"))&&types.size()>=3){
            String key=semanticTypeNoun(types.get(1)),value=semanticTypeNoun(types.get(2));
            String by=key.equals("identifier")||key.equals("id")?"Id":key.equals("string")?"Name":capitalize(key);
            String values=Set.of("integer","long","double","float","boolean","byte","short","character","string","object").contains(value)?"values":pluralize(value);
            int confidence=values.equals("values")?72:90;
            return new Candidate(values+"By"+by,confidence,"generic map types "+types.get(1)+" -> "+types.get(2));
        }
        return null;
    }

    private String dominantSemanticNoun(ClassFile cf){
        Map<String,Integer> score=new HashMap<>();
        Set<String> stop=Set.of("failed","error","unable","missing","invalid","the","this","that","with","from","into","slot","resource","value","data","object","class","java");
        for(MemberInfo m:cf.methods()){AttributeInfo a=m.attribute("Code");if(a==null)continue;try{CodeAttribute c=CodeAttribute.parse(a,cf.constantPool());for(Instruction i:BytecodeDecoder.decode(c.code())){
            if(i.opcode()==18||i.opcode()==19){Object v=cf.constantPool().constant(i.opcode()==18?i.u1(0):i.u2(0));if(v instanceof String str){Matcher w=WORD.matcher(str);while(w.find()){String x=w.group().toLowerCase(Locale.ROOT);if(x.length()>=4&&!VERBS.contains(x)&&!stop.contains(x))score.merge(singular(x),2,Integer::sum);}}}
            if(i.opcode()>=182&&i.opcode()<=185){try{ConstantPool.MemberRef r=cf.constantPool().memberRef(i.u2(0));String owner=semanticTypeNoun(simpleInternal(r.owner()));if(owner.length()>3&&!Set.of("string","object","system","iterator","list","map","set").contains(owner))score.merge(singular(owner),1,Integer::sum);}catch(RuntimeException ignored){}}
        }}catch(RuntimeException ignored){}}
        for(MemberInfo f:cf.fields()){try{String t=semanticTypeNoun(simpleJavaType(DescriptorParser.fieldType(f.descriptor())));if(t.length()>3&&!Set.of("string","object","list","map","set").contains(t))score.merge(singular(t),1,Integer::sum);}catch(RuntimeException ignored){}}
        return score.entrySet().stream().filter(e->e.getValue()>=3).max(Map.Entry.<String,Integer>comparingByValue().thenComparing(Map.Entry::getKey)).map(Map.Entry::getKey).orElse(null);
    }

    private String classRole(ClassFile cf){
        Map<String,Integer> verbs=new HashMap<>();
        for(MemberInfo m:cf.methods()){AttributeInfo a=m.attribute("Code");if(a==null)continue;try{CodeAttribute c=CodeAttribute.parse(a,cf.constantPool());for(Instruction i:BytecodeDecoder.decode(c.code()))if(i.opcode()>=182&&i.opcode()<=185){try{String n=cf.constantPool().memberRef(i.u2(0)).name().toLowerCase(Locale.ROOT);for(String v:VERBS)if(n.startsWith(v))verbs.merge(v,1,Integer::sum);}catch(RuntimeException ignored){}}}catch(RuntimeException ignored){}}
        int registry=verbs.getOrDefault("register",0)+verbs.getOrDefault("add",0)+verbs.getOrDefault("remove",0)+verbs.getOrDefault("release",0);
        int loader=verbs.getOrDefault("load",0)+verbs.getOrDefault("read",0)+verbs.getOrDefault("open",0);
        int renderer=verbs.getOrDefault("render",0)+verbs.getOrDefault("draw",0);
        int codec=verbs.getOrDefault("encode",0)+verbs.getOrDefault("decode",0);
        int factory=verbs.getOrDefault("create",0)+verbs.getOrDefault("build",0);
        int handler=verbs.getOrDefault("handle",0)+verbs.getOrDefault("process",0)+verbs.getOrDefault("receive",0);
        int max=Math.max(registry,Math.max(loader,Math.max(renderer,Math.max(codec,Math.max(factory,handler)))));
        if(max<2)return null;if(registry==max)return "Registry";if(loader==max)return "Loader";if(renderer==max)return "Renderer";if(codec==max)return "Codec";if(factory==max)return "Factory";return "Handler";
    }

    private static String semanticTypeNoun(String simple){String x=simple;if(x.startsWith("Abstract")&&x.length()>8)x=x.substring(8);if(x.endsWith("Impl")&&x.length()>4)x=x.substring(0,x.length()-4);if(x.equals("Identifier"))return "identifier";if(x.endsWith("Id")||x.equals("ID"))return "id";return decap(x);}
    private static String pluralize(String x){if(x.endsWith("y")&&x.length()>1&&!"aeiou".contains(""+x.charAt(x.length()-2)))return x.substring(0,x.length()-1)+"ies";if(x.endsWith("s"))return x;return x+"s";}
    private static String singular(String x){if(x.endsWith("ies")&&x.length()>3)return x.substring(0,x.length()-3)+"y";if(x.endsWith("s")&&x.length()>4)return x.substring(0,x.length()-1);return x;}

    public static String parameterBase(String javaType,int index){
        String t=simpleJavaType(javaType);
        if(t.equals("String"))return "text";if(t.equals("Path"))return "path";if(t.equals("File"))return "file";
        if(t.equals("Executor")||t.endsWith("Executor"))return "executor";if(t.equals("Identifier")||t.endsWith("Id"))return "id";
        if(t.equals("ResourceManager"))return "resourceManager";if(t.equals("Class"))return "type";if(t.equals("Throwable")||t.endsWith("Exception"))return "exception";
        if(t.equals("boolean"))return "flag";if(t.equals("int"))return index==0?"value":"index";if(t.equals("long"))return "value";
        if(t.endsWith("[]"))return "values";if(t.length()>1&&!Character.isLowerCase(t.charAt(0)))return decap(t);
        return "param"+index;
    }

    private static boolean isParameterLoad(Instruction i){int op=i.opcode();return(op>=21&&op<=45);}
    private static boolean isReturn(int op){return op>=172&&op<=176;}
    private static String simpleJavaType(String t){String x=t;while(x.endsWith("[]"))x=x.substring(0,x.length()-2);int p=Math.max(x.lastIndexOf('.'),x.lastIndexOf('$'));return p<0?x:x.substring(p+1);}
    private static String simpleInternal(String t){int p=Math.max(t.lastIndexOf('/'),t.lastIndexOf('$'));return p<0?t:t.substring(p+1);}
    private static String decap(String s){if(s==null||s.isEmpty())return "value";return Character.toLowerCase(s.charAt(0))+s.substring(1);}
    private static String capitalize(String s){if(s==null||s.isEmpty())return "Value";return Character.toUpperCase(s.charAt(0))+s.substring(1);}
    private static String shorten(String s){String x=s.replace('\n',' ').replace('\r',' ');return x.length()>48?x.substring(0,45)+"...":x;}
}
