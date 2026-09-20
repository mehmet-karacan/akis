package tr.com.innova.akis.knowledge;

import java.math.BigDecimal;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Bounded scalar/predicate language. No statements, subqueries or arbitrary function calls. */
public final class MappingSql {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final int MAX_TEXT = 16_384, MAX_NODES = 1000, MAX_DEPTH = 32;
    private static final Map<String, int[]> FUNCTIONS = Map.ofEntries(
            fn("TO_CHAR",1,3), fn("TO_DATE",1,3), fn("TO_TIMESTAMP",1,3), fn("TO_NUMBER",1,3),
            fn("UPPER",1,1), fn("LOWER",1,1), fn("TRIM",1,1), fn("LTRIM",1,2), fn("RTRIM",1,2),
            fn("SUBSTR",2,3), fn("REPLACE",2,3), fn("LENGTH",1,1), fn("INSTR",2,4),
            fn("LPAD",2,3), fn("RPAD",2,3), fn("NVL",2,2), fn("NVL2",3,3), fn("NULLIF",2,2),
            fn("COALESCE",2,64), fn("GREATEST",2,64), fn("LEAST",2,64),
            fn("ROUND",1,2), fn("TRUNC",1,2), fn("ABS",1,1), fn("CEIL",1,1), fn("FLOOR",1,1),
            fn("MOD",2,2), fn("ADD_MONTHS",2,2), fn("LAST_DAY",1,1), fn("MONTHS_BETWEEN",2,2));
    private static Map.Entry<String,int[]> fn(String name,int min,int max) { return Map.entry(name,new int[]{min,max}); }
    public record Source(String object, String alias, Set<String> columns) {
        public Source {
            StagedMappingDefinition.identifier(object); StagedMappingDefinition.identifier(alias);
            columns = Set.copyOf(columns);
            columns.forEach(StagedMappingDefinition::identifier);
        }
    }
    public record Reference(String object, String column) { }
    public record BoundSql(String sql, List<Object> parameters, Set<Reference> references) {
        public BoundSql { parameters=Collections.unmodifiableList(new ArrayList<>(parameters)); references=Set.copyOf(references); }
    }

    /** Parses using aliases, but persists stable object references in the AST. */
    public static JsonNode parse(String text,List<Source> sources,boolean predicate) {
        var parser=new Parser(text,new Catalog(sources));
        JsonNode ast=parser.expression(0,0);
        if(parser.peek().kind()!=Kind.EOF) throw invalid("Beklenmeyen SQL parçası",parser.peek().position());
        // One validator is used for both typed text and persisted/untrusted AST.
        render(ast,sources,predicate);
        return ast;
    }

    public static BoundSql render(JsonNode expression,List<Source> sources,boolean predicate) {
        var renderer=new Renderer(new Catalog(sources));
        Part result=renderer.node(expression,0);
        if(predicate != result.predicate()) throw invalid(predicate?"Filtre koşul olmalıdır":"Kolon eşlemesi değer ifadesi olmalıdır",0);
        return new BoundSql(result.sql(),renderer.parameters,renderer.references);
    }

    /** Syntax/role check without claiming that columns exist in the live catalog. */
    public static void validate(JsonNode expression,Map<String,String> aliases,boolean predicate) {
        Map<String,Set<String>> columns=new LinkedHashMap<>();aliases.keySet().forEach(id->columns.put(id,new LinkedHashSet<>()));
        collectReferences(expression,columns,0,new int[]{4096});
        render(expression,aliases.entrySet().stream().map(entry->new Source(entry.getKey(),entry.getValue(),columns.get(entry.getKey()))).toList(),predicate);
    }
    private static void collectReferences(JsonNode node,Map<String,Set<String>> columns,int depth,int[] budget) {
        if(node==null || depth>64 || --budget[0]<0) throw invalid("İfade boyutu sınırı aşıldı",0);
        if(node.isObject()) {
            if("COLUMN".equals(node.path("kind").asText())) {
                String object=text(node,"dataset"),column=text(node,"column");
                if(!columns.containsKey(object)) throw invalid("İfade yalnız kaynak kolonlarını kullanabilir",0);
                columns.get(object).add(column);
            }
            node.properties().forEach(entry->collectReferences(entry.getValue(),columns,depth+1,budget));
        } else if(node.isArray()) for(JsonNode child:node) collectReferences(child,columns,depth+1,budget);
    }
    private record Part(String sql, boolean predicate) { }

    private static final class Catalog {
        final Map<String,Source> aliases=new LinkedHashMap<>(), objects=new LinkedHashMap<>();
        Catalog(List<Source> sources) {
            if(sources==null || sources.isEmpty() || sources.size()>16) throw invalid("1-16 kaynak gerekir",0);
            for(Source source:sources) {
                if(aliases.put(source.alias(),source)!=null || objects.put(source.object(),source)!=null)
                    throw invalid("Kaynak kimlikleri ve takma adları benzersiz olmalıdır",0);
            }
        }
        Source alias(String alias) {
            Source result=aliases.get(alias);
            if(result==null) throw invalid("Kaynak takma adı bulunamadı: "+alias,0);
            return result;
        }
        Source reference(String object,String column) {
            Source source=objects.get(object);
            if(source==null || !source.columns().contains(column)) throw invalid("Kaynak kolon bulunamadı: "+object+"."+column,0);
            return source;
        }
    }

    private record Token(String text, Kind kind, int position) { }
    private enum Kind { WORD, STRING, NUMBER, SYMBOL, EOF }
    private static List<Token> lex(String text) {
        if(text==null || text.isBlank() || text.length()>MAX_TEXT) throw invalid("SQL ifade uzunluğu geçersiz",0);
        List<Token> tokens=new ArrayList<>();
        int index=0;
        while(index<text.length()) {
            char ch=text.charAt(index); int start=index;
            if(Character.isWhitespace(ch)) { index++; continue; }
            if(ch=='\'') {
                StringBuilder value=new StringBuilder(); boolean closed=false; index++;
                while(index<text.length()) {
                    char next=text.charAt(index++);
                    if(next=='\'') {
                        if(index<text.length() && text.charAt(index)=='\'') { value.append('\''); index++; }
                        else { closed=true; break; }
                    } else value.append(next);
                }
                if(!closed) throw invalid("Metin sabiti kapatılmadı",start);
                tokens.add(new Token(value.toString(),Kind.STRING,start));
            } else if(ch>='0' && ch<='9' || ch=='.' && index+1<text.length() && Character.isDigit(text.charAt(index+1))) {
                boolean dot=false;
                while(index<text.length()) {
                    char next=text.charAt(index);
                    if(next=='.' && !dot) { dot=true; index++; }
                    else if(next>='0' && next<='9') index++;
                    else break;
                }
                if(index<text.length() && (text.charAt(index)=='e' || text.charAt(index)=='E')) {
                    index++;
                    if(index<text.length() && (text.charAt(index)=='+' || text.charAt(index)=='-')) index++;
                    int digits=index;
                    while(index<text.length() && Character.isDigit(text.charAt(index))) index++;
                    if(index==digits) throw invalid("Geçersiz sayısal üs",start);
                }
                String value=text.substring(start,index);
                if(value.length()>128) throw invalid("Sayı sabiti çok uzun",start);
                BigDecimal number;
                try { number=new BigDecimal(value); } catch(NumberFormatException ex) { throw invalid("Geçersiz sayı",start); }
                if(Math.abs((long)number.scale())>1000) throw invalid("Sayısal üs sınırı aşıldı",start);
                tokens.add(new Token(value,Kind.NUMBER,start));
            } else if(ch>='a' && ch<='z' || ch>='A' && ch<='Z' || ch=='_') {
                index++;
                while(index<text.length() && (Character.isLetterOrDigit(text.charAt(index)) || "_$#".indexOf(text.charAt(index))>=0)) index++;
                String word=text.substring(start,index).toUpperCase(Locale.ROOT);
                if(word.length()>128) throw invalid("Ad çok uzun",start);
                tokens.add(new Token(word,Kind.WORD,start));
            } else {
                String pair=index+1<text.length()?text.substring(index,index+2):"";
                if(Set.of("--","/*","*/").contains(pair)) throw invalid("İfadede SQL yorumu kullanılamaz",start);
                if(Set.of("||","<=",">=","<>","!=").contains(pair)) { tokens.add(new Token(pair,Kind.SYMBOL,start)); index+=2; }
                else if("()+-*/=<>.,".indexOf(ch)>=0) { tokens.add(new Token(String.valueOf(ch),Kind.SYMBOL,start)); index++; }
                else throw invalid("Desteklenmeyen SQL karakteri",start);
            }
            if(tokens.size()>4096) throw invalid("SQL ifade boyutu sınırı aşıldı",start);
        }
        tokens.add(new Token("<EOF>",Kind.EOF,text.length())); return tokens;
    }

    private static final class Parser {
        final List<Token> tokens; final Catalog catalog; int index=0,nodes=0;
        Parser(String text,Catalog catalog) { tokens=lex(text);this.catalog=catalog; }
        Token peek() { return tokens.get(index); }
        Token take() { if(peek().kind()==Kind.EOF) throw invalid("SQL ifadesi tamamlanmadı",peek().position());return tokens.get(index++); }
        boolean eat(String text) { if(peek().text().equals(text) && peek().kind()!=Kind.STRING) { index++;return true; } return false; }
        void expect(String text) { if(!eat(text)) throw invalid("Beklenen: "+text,peek().position()); }
        ObjectNode make(String kind) { if(++nodes>MAX_NODES) throw invalid("İfade boyutu sınırı aşıldı",peek().position());return JSON.objectNode().put("kind",kind); }
        JsonNode expression(int minimum,int depth) {
            if(depth>MAX_DEPTH) throw invalid("İfade iç içe geçme sınırı aşıldı",peek().position());
            JsonNode left=prefix(depth+1);
            while(true) {
                String op=peek().kind()==Kind.STRING?"":peek().text();
                int precedence=precedence(op);
                if(precedence<minimum) break;
                take();
                boolean negate=false;
                if(op.equals("NOT")) { negate=true; op=take().text(); if(!Set.of("IN","LIKE","BETWEEN").contains(op)) throw invalid("NOT ardından IN, LIKE veya BETWEEN gerekir",peek().position()); }
                if(op.equals("IS")) {
                    boolean not=eat("NOT");expect("NULL");
                    var node=make("UNARY").put("operator",not?"IS NOT NULL":"IS NULL"); node.set("argument",left);left=node;
                } else if(op.equals("IN")) {
                    expect("(");var args=JSON.arrayNode();
                    do { args.add(expression(4,depth+1)); if(args.size()>64) throw invalid("IN listesi en fazla 64 değer içerebilir",peek().position()); } while(eat(","));
                    expect(")");var node=make("IN").put("negated",negate);node.set("argument",left);node.set("values",args);left=node;
                } else if(op.equals("BETWEEN")) {
                    var lower=expression(4,depth+1);expect("AND");var upper=expression(4,depth+1);
                    var node=make("BETWEEN").put("negated",negate);node.set("argument",left);node.set("lower",lower);node.set("upper",upper);left=node;
                } else {
                    JsonNode right=expression(precedence+1,depth+1);
                    var node=make("BINARY").put("operator",negate?"NOT LIKE":op.equals("!=")?"<>":op);
                    node.set("left",left);node.set("right",right);
                    if(op.equals("LIKE") && eat("ESCAPE")) {
                        Token escape=take();
                        if(escape.kind()!=Kind.STRING || escape.text().length()!=1) throw invalid("ESCAPE tek karakter olmalıdır",escape.position());
                        node.put("escape",escape.text());
                    }
                    left=node;
                }
            }
            return left;
        }
        JsonNode prefix(int depth) {
            if(depth>MAX_DEPTH) throw invalid("İfade iç içe geçme sınırı aşıldı",peek().position());
            Token token=take(); String text=token.text();
            if(token.kind()==Kind.STRING) return make("LITERAL").put("value",text);
            // JSON numbers are rounded by JavaScript before a definition is saved.
            // Keep the exact numeric lexeme across the browser/API boundary.
            if(token.kind()==Kind.NUMBER) return make("NUMBER").put("value",text);
            if(text.equals("NULL")) return make("LITERAL").putNull("value");
            if(text.equals("(")) { var value=expression(0,depth+1);expect(")");return value; }
            if(Set.of("+","-","NOT").contains(text)) {
                var node=make("UNARY").put("operator",text);node.set("argument",expression(text.equals("NOT")?3:7,depth+1));return node;
            }
            if(text.equals("CASE")) {
                var node=make("CASE");var branches=JSON.arrayNode();
                JsonNode subject=peek().text().equals("WHEN")?null:expression(0,depth+1);
                while(eat("WHEN")) {
                    var condition=expression(0,depth+1);
                    if(subject!=null) { var comparison=make("BINARY").put("operator","=");comparison.set("left",subject);comparison.set("right",condition);condition=comparison; }
                    expect("THEN");var branch=JSON.objectNode();branch.set("when",condition);branch.set("then",expression(0,depth+1));branches.add(branch);
                    if(branches.size()>32) throw invalid("CASE dal sınırı aşıldı",peek().position());
                }
                if(branches.isEmpty()) throw invalid("CASE WHEN gerekir",peek().position());
                node.set("branches",branches);node.set("else",eat("ELSE")?expression(0,depth+1):make("LITERAL").putNull("value"));expect("END");return node;
            }
            if(token.kind()!=Kind.WORD) throw invalid("Değer ifadesi bekleniyor",token.position());
            if(eat("(")) {
                if(!FUNCTIONS.containsKey(text)) throw invalid("Desteklenmeyen fonksiyon: "+text,token.position());
                var args=JSON.arrayNode();
                if(!eat(")")) { do { args.add(expression(0,depth+1));if(args.size()>64) throw invalid("Argüman sınırı aşıldı",peek().position()); } while(eat(","));expect(")"); }
                var node=make("CALL").put("function",text);node.set("args",args);return node;
            }
            expect(".");Token column=take();
            if(column.kind()!=Kind.WORD) throw invalid("Kolon adı bekleniyor",column.position());
            Source source=catalog.alias(text);catalog.reference(source.object(),column.text());
            return make("COLUMN").put("dataset",source.object()).put("column",column.text());
        }
    }
    private static int precedence(String op) {
        return switch(op) { case "OR"->1;case "AND"->2;case "=","<>","!=","<",">","<=",">=","LIKE","IS","IN","BETWEEN","NOT"->3;case "||"->4;case "+","-"->5;case "*","/"->6;default->-1; };
    }

    private static final class Renderer {
        final Catalog catalog;final List<Object> parameters=new ArrayList<>();final Set<Reference> references=new LinkedHashSet<>();int remaining=MAX_NODES;
        Renderer(Catalog catalog) { this.catalog=catalog; }
        Part node(JsonNode node,int depth) {
            if(node==null || !node.isObject() || depth>MAX_DEPTH || --remaining<0) throw invalid("SQL ifade yapısı veya boyutu geçersiz",0);
            String kind=text(node,"kind");
            switch(kind) {
                case "COLUMN": {
                    keys(node,"kind","dataset","column");String object=text(node,"dataset"),column=text(node,"column");
                    Source source=catalog.reference(object,column);references.add(new Reference(object,column));
                    return new Part(quote(source.alias())+"."+quote(column),false);
                }
                case "LITERAL": {
                    keys(node,"kind","value");JsonNode value=node.get("value");
                    if(value==null) throw invalid("Sabit değer zorunludur",0);
                    if(value.isNull()) return new Part("NULL",false);
                    if(value.isTextual() && value.asText().length()<=MAX_TEXT) parameters.add(value.asText());
                    else if(value.isNumber() && value.asText().length()<=128) {
                        var number=new BigDecimal(value.asText());if(Math.abs((long)number.scale())>1000) throw invalid("Sayısal üs sınırı aşıldı",0);parameters.add(number);
                    } else throw invalid("SQL sabiti yalnız metin, sayı veya NULL olabilir",0);
                    return new Part("?",false);
                }
                case "NUMBER": {
                    keys(node,"kind","value");String lexeme=text(node,"value");
                    if(lexeme.length()>128 || !lexeme.matches("(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")) throw invalid("Sayısal sabit geçersiz",0);
                    var number=new BigDecimal(lexeme);
                    if(Math.abs((long)number.scale())>1000) throw invalid("Sayısal üs sınırı aşıldı",0);
                    parameters.add(number);return new Part("?",false);
                }
                case "CALL": {
                    keys(node,"kind","function","args");String name=text(node,"function");int[] arity=FUNCTIONS.get(name);var args=node.path("args");
                    if(arity==null || !args.isArray() || args.size()<arity[0] || args.size()>arity[1]) throw invalid("Fonksiyon veya argüman sayısı geçersiz: "+name,0);
                    List<String> rendered=new ArrayList<>();for(JsonNode arg:args) rendered.add(scalar(arg,depth+1));
                    return new Part(name+"("+String.join(", ",rendered)+")",false);
                }
                case "UNARY": {
                    keys(node,"kind","operator","argument");String op=text(node,"operator");Part arg=node(node.get("argument"),depth+1);
                    if(op.equals("NOT") && arg.predicate()) return new Part("(NOT "+arg.sql()+")",true);
                    if(Set.of("+","-").contains(op) && !arg.predicate()) return new Part("("+op+arg.sql()+")",false);
                    if(Set.of("IS NULL","IS NOT NULL").contains(op) && !arg.predicate()) return new Part("("+arg.sql()+" "+op+")",true);
                    throw invalid("Tekli işlem geçersiz",0);
                }
                case "BINARY": {
                    keys(node,"kind","operator","left","right","escape");String op=text(node,"operator");
                    Part left=node(node.get("left"),depth+1),right=node(node.get("right"),depth+1);
                    boolean logical=Set.of("AND","OR").contains(op);
                    boolean comparison=Set.of("=","<>","<",">","<=",">=","LIKE","NOT LIKE").contains(op);
                    if(!logical && !comparison && !Set.of("+","-","*","/","||").contains(op)) throw invalid("İşlem desteklenmiyor",0);
                    if(left.predicate()!=logical || right.predicate()!=logical) throw invalid("Koşul ve değer ifadeleri karıştırılamaz",0);
                    String escape="";
                    if(node.has("escape")) {
                        String character=text(node,"escape");
                        if(!Set.of("LIKE","NOT LIKE").contains(op) || character.length()!=1) throw invalid("ESCAPE yalnız LIKE için tek karakter olabilir",0);
                        parameters.add(character); escape=" ESCAPE ?";
                    }
                    return new Part("("+left.sql()+" "+op+" "+right.sql()+escape+")",logical||comparison);
                }
                case "IN": {
                    keys(node,"kind","argument","values","negated");String argument=scalar(node.get("argument"),depth+1);var values=node.path("values");
                    if(!values.isArray() || values.isEmpty() || values.size()>64 || !node.path("negated").isBoolean()) throw invalid("IN listesi geçersiz",0);
                    List<String> rendered=new ArrayList<>();for(JsonNode value:values) rendered.add(scalar(value,depth+1));
                    return new Part("("+argument+(node.path("negated").asBoolean()?" NOT IN (":" IN (")+String.join(", ",rendered)+"))",true);
                }
                case "BETWEEN": {
                    keys(node,"kind","argument","lower","upper","negated");if(!node.path("negated").isBoolean()) throw invalid("BETWEEN geçersiz",0);
                    String argument=scalar(node.get("argument"),depth+1),lower=scalar(node.get("lower"),depth+1),upper=scalar(node.get("upper"),depth+1);
                    return new Part("("+argument+(node.path("negated").asBoolean()?" NOT BETWEEN ":" BETWEEN ")+lower+" AND "+upper+")",true);
                }
                case "CASE": {
                    keys(node,"kind","branches","else");var branches=node.path("branches");
                    if(!branches.isArray() || branches.isEmpty() || branches.size()>32) throw invalid("CASE dalları geçersiz",0);
                    StringBuilder sql=new StringBuilder("(CASE");
                    for(var branch:branches) {
                        keys(branch,"when","then");Part condition=node(branch.get("when"),depth+1);
                        if(!condition.predicate()) throw invalid("CASE WHEN koşul gerektirir",0);
                        sql.append(" WHEN ").append(condition.sql()).append(" THEN ").append(scalar(branch.get("then"),depth+1));
                    }
                    return new Part(sql.append(" ELSE ").append(scalar(node.get("else"),depth+1)).append(" END)").toString(),false);
                }
                default: throw invalid("Desteklenmeyen SQL ifade türü",0);
            }
        }
        String scalar(JsonNode ast,int depth) { Part part=node(ast,depth);if(part.predicate()) throw invalid("Değer ifadesi gerekir",0);return part.sql(); }
    }
    private static String quote(String name) { return "\""+StagedMappingDefinition.identifier(name)+"\""; }
    private static String text(JsonNode node,String key) {
        if(!node.path(key).isTextual() || node.path(key).asText().isBlank()) throw invalid("SQL ifade alanı eksik: "+key,0);
        return node.path(key).asText();
    }
    private static void keys(JsonNode node,String... keys) {
        if(node==null || !node.isObject() || !Set.of(keys).containsAll(node.propertyNames())) throw invalid("Desteklenmeyen SQL ifade alanı",0);
    }
    private static IllegalArgumentException invalid(String message,int position) { return new IllegalArgumentException(message+" (konum "+(position+1)+")."); }
}
