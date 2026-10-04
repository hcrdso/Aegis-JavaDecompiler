package dev.aegis.decompile;

import dev.aegis.rename.MappingSet;
import java.util.*;

/** Best-effort parser for the JVM Signature attribute. No external libraries. */
final class GenericSignatureParser {
    record MethodSig(String typeParameters, List<String> parameterTypes, String returnType, List<String> throwsTypes) {}
    record ClassSig(String typeParameters, String superType, List<String> interfaces) {}

    static String fieldType(String sig, MappingSet map) {
        if(sig==null||sig.isBlank())return null;
        try{P p=new P(sig,map);String t=p.type();return p.end()?t:null;}catch(RuntimeException ex){return null;}
    }

    static MethodSig method(String sig, MappingSet map) {
        if(sig==null||sig.isBlank())return null;
        try{
            P p=new P(sig,map);String tp=p.typeParams();p.expect('(');ArrayList<String> ps=new ArrayList<>();while(p.peek()!=')')ps.add(p.type());p.expect(')');
            String ret;if(p.peek()=='V'){p.take();ret="void";}else ret=p.type();
            ArrayList<String> th=new ArrayList<>();while(!p.end()&&p.peek()=='^'){p.take();th.add(p.type());}
            return new MethodSig(tp,List.copyOf(ps),ret,List.copyOf(th));
        }catch(RuntimeException ex){return null;}
    }

    static ClassSig clazz(String sig, MappingSet map) {
        if(sig==null||sig.isBlank())return null;
        try{P p=new P(sig,map);String tp=p.typeParams();String sup=p.type();ArrayList<String> it=new ArrayList<>();while(!p.end())it.add(p.type());return new ClassSig(tp,sup,List.copyOf(it));}catch(RuntimeException ex){return null;}
    }

    private static final class P {
        final String s; final MappingSet map; int i;
        P(String s,MappingSet map){this.s=s;this.map=map;}
        boolean end(){return i>=s.length();} char peek(){if(end())throw new IllegalArgumentException("eof");return s.charAt(i);} char take(){char c=peek();i++;return c;} void expect(char c){if(take()!=c)throw new IllegalArgumentException("expected "+c);}

        String typeParams(){
            if(end()||peek()!='<')return "";take();ArrayList<String> out=new ArrayList<>();
            while(peek()!='>'){
                String name=identUntil(':');expect(':');ArrayList<String> bounds=new ArrayList<>();
                if(peek()!=':')bounds.add(type());
                while(peek()==':'){take();bounds.add(type());}
                bounds.removeIf("java.lang.Object"::equals);
                out.add(name+(bounds.isEmpty()?"":" extends "+String.join(" & ",bounds)));
            }
            expect('>');return "<"+String.join(", ",out)+">";
        }

        String type(){
            char c=take();return switch(c){
                case 'B'->"byte";case 'C'->"char";case 'D'->"double";case 'F'->"float";case 'I'->"int";case 'J'->"long";case 'S'->"short";case 'Z'->"boolean";
                case '['->type()+"[]";
                case 'T'->{String n=identUntil(';');expect(';');yield n;}
                case 'L'->classType();
                default->throw new IllegalArgumentException("bad type "+c);
            };}

        String classType(){
            StringBuilder internal=new StringBuilder();while(!end()){char c=peek();if(c=='<'||c==';'||c=='.')break;internal.append(take());}
            StringBuilder out=new StringBuilder(mapped(internal.toString()));
            if(!end()&&peek()=='<')out.append(typeArgs());
            while(!end()&&peek()=='.'){
                take();String inner=identUntil('<',';','.');out.append('.').append(inner);
                if(!end()&&peek()=='<')out.append(typeArgs());
            }
            expect(';');return out.toString();
        }

        String typeArgs(){
            expect('<');ArrayList<String> args=new ArrayList<>();while(peek()!='>'){
                char c=peek();if(c=='*'){take();args.add("?");}
                else if(c=='+'){take();args.add("? extends "+type());}
                else if(c=='-'){take();args.add("? super "+type());}
                else args.add(type());
            }expect('>');return "<"+String.join(", ",args)+">";
        }

        String mapped(String internal){return map.className(internal).replace('/','.').replace('$','.');}
        String identUntil(char c){int st=i;while(peek()!=c)i++;return s.substring(st,i);}
        String identUntil(char... cs){int st=i;outer:while(!end()){char x=peek();for(char c:cs)if(x==c)break outer;i++;}return s.substring(st,i);}
    }
}
