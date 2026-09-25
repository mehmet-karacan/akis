package tr.com.innova.akis.knowledge;

import java.util.*;
import java.util.regex.*;

/** Safe, finite metadata substitution syntax. It never evaluates Java, scripts or reflection. */
public final class AkisKmTemplate {
    private static final Pattern EXPRESSION = Pattern.compile("\\{\\{\\s*akisRef\\.([a-z][A-Za-z0-9]*)\\((.*?)\\)\\s*}}", Pattern.DOTALL);
    private static final Set<String> FUNCTIONS = Set.of("table", "columns", "tables", "filters", "option", "context", "integration", "check");
    private AkisKmTemplate() { }

    @FunctionalInterface public interface Resolver { String resolve(String function, List<String> arguments); }
    public record Reference(String function, List<String> arguments) {
        public Reference { arguments = List.copyOf(arguments); }
    }

    public static List<Reference> validate(String template) {
        if (template == null || template.isBlank() || template.length() > 32_768) throw new IllegalArgumentException("KM komut şablonu boş veya çok büyük.");
        if (template.contains("<%") || template.contains("%>")) throw new IllegalArgumentException("KM komutunda Java/JSP ifadesi kullanılamaz.");
        List<Reference> references = new ArrayList<>();
        Matcher matcher = EXPRESSION.matcher(template);
        int cursor = 0;
        while (matcher.find()) {
            String between = template.substring(cursor, matcher.start());
            if (between.contains("{{") || between.contains("}}")) throw new IllegalArgumentException("Geçersiz akisRef ifadesi.");
            String function = matcher.group(1);
            if (!FUNCTIONS.contains(function)) throw new IllegalArgumentException("Desteklenmeyen akisRef fonksiyonu: " + function);
            references.add(new Reference(function, arguments(matcher.group(2))));
            cursor = matcher.end();
        }
        String tail = template.substring(cursor);
        if (tail.contains("{{") || tail.contains("}}")) throw new IllegalArgumentException("Tamamlanmamış akisRef ifadesi.");
        return List.copyOf(references);
    }

    public static String render(String template, Resolver resolver) {
        Objects.requireNonNull(resolver);
        validate(template);
        Matcher matcher = EXPRESSION.matcher(template);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String value = Objects.requireNonNull(resolver.resolve(matcher.group(1), arguments(matcher.group(2))), "akisRef null döndü.");
            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static List<String> arguments(String source) {
        List<String> result = new ArrayList<>();
        int index = 0;
        while (index < source.length()) {
            while (index < source.length() && Character.isWhitespace(source.charAt(index))) index++;
            if (index == source.length()) break;
            if (source.charAt(index) != '"') throw new IllegalArgumentException("akisRef argümanları çift tırnaklı sabit metin olmalıdır.");
            int start = ++index; StringBuilder value = new StringBuilder(); boolean closed = false;
            while (index < source.length()) {
                char character = source.charAt(index++);
                if (character == '\\') {
                    if (index >= source.length()) throw new IllegalArgumentException("Eksik akisRef kaçış dizisi.");
                    char escaped = source.charAt(index++);
                    value.append(switch (escaped) { case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t'; case '"', '\\' -> escaped; default -> throw new IllegalArgumentException("Desteklenmeyen akisRef kaçış dizisi."); });
                } else if (character == '"') { closed = true; break; }
                else value.append(character);
            }
            if (!closed || value.length() > 1024) throw new IllegalArgumentException("Geçersiz akisRef argümanı: " + source.substring(Math.max(0, start - 1)));
            result.add(value.toString());
            while (index < source.length() && Character.isWhitespace(source.charAt(index))) index++;
            if (index == source.length()) break;
            if (source.charAt(index++) != ',') throw new IllegalArgumentException("akisRef argümanları virgülle ayrılmalıdır.");
        }
        if (result.size() > 8) throw new IllegalArgumentException("akisRef en fazla 8 argüman kabul eder.");
        return result;
    }
}
