package tr.com.innova.akis.metadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Oracle-aware lexical extraction of named binds without interpreting SQL. */
public final class NamedBindParser {

    private NamedBindParser() {
    }

    public static List<String> parse(String sql) {
        if (sql == null) {
            throw new IllegalArgumentException("SQL is required.");
        }
        List<String> binds = new ArrayList<>();
        int index = 0;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == '-' && character(sql, index + 1) == '-') {
                int lineEnd = sql.indexOf('\n', index + 2);
                index = lineEnd < 0 ? sql.length() : lineEnd + 1;
                continue;
            }
            if (current == '/' && character(sql, index + 1) == '*') {
                int commentEnd = sql.indexOf("*/", index + 2);
                if (commentEnd < 0) throw malformed();
                index = commentEnd + 2;
                continue;
            }
            if ((current == 'n' || current == 'N')
                    && (character(sql, index + 1) == 'q' || character(sql, index + 1) == 'Q')
                    && character(sql, index + 2) == '\'') {
                index = skipAlternativeQuote(sql, index + 1);
                continue;
            }
            if ((current == 'q' || current == 'Q') && character(sql, index + 1) == '\'') {
                index = skipAlternativeQuote(sql, index);
                continue;
            }
            if (current == '\'' || current == '"') {
                index = skipQuoted(sql, index, current);
                continue;
            }
            if (current == ':' && character(sql, index + 1) != '='
                    && (index == 0 || sql.charAt(index - 1) != ':')
                    && isBindStart(character(sql, index + 1))) {
                int end = index + 2;
                while (isBindPart(character(sql, end))) end++;
                binds.add(sql.substring(index + 1, end).toUpperCase(Locale.ROOT));
                index = end;
                continue;
            }
            index++;
        }
        return List.copyOf(binds);
    }

    private static int skipQuoted(String sql, int start, char quote) {
        int index = start + 1;
        while (index < sql.length()) {
            if (sql.charAt(index) == quote) {
                if (character(sql, index + 1) == quote) {
                    index += 2;
                    continue;
                }
                return index + 1;
            }
            index++;
        }
        throw malformed();
    }

    private static int skipAlternativeQuote(String sql, int qIndex) {
        if (qIndex + 2 >= sql.length()) throw malformed();
        char opener = sql.charAt(qIndex + 2);
        char closer = switch (opener) {
            case '[' -> ']';
            case '(' -> ')';
            case '{' -> '}';
            case '<' -> '>';
            default -> opener;
        };
        int index = qIndex + 3;
        while (index + 1 < sql.length()) {
            if (sql.charAt(index) == closer && sql.charAt(index + 1) == '\'') {
                return index + 2;
            }
            index++;
        }
        throw malformed();
    }

    private static char character(String value, int index) {
        return index >= 0 && index < value.length() ? value.charAt(index) : '\0';
    }

    private static boolean isBindStart(char value) {
        return value >= 'A' && value <= 'Z' || value >= 'a' && value <= 'z';
    }

    private static boolean isBindPart(char value) {
        return isBindStart(value) || value >= '0' && value <= '9'
                || value == '_' || value == '$' || value == '#';
    }

    private static IllegalArgumentException malformed() {
        return new IllegalArgumentException("SQL contains an unterminated quote or comment.");
    }
}
