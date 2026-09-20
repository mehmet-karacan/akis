package tr.com.innova.akis.metadata;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;

/** Bounded scalar SELECT contract shared by authoring, testing and execution. */
public final class VariableQueryPolicy {
    private static final Set<String> FORBIDDEN = Set.of("INSERT", "UPDATE", "DELETE", "MERGE", "CREATE",
        "ALTER", "DROP", "TRUNCATE", "GRANT", "REVOKE", "COMMIT", "ROLLBACK", "EXECUTE", "CALL",
        "BEGIN", "DECLARE", "INTO", "LOCK", "NEXTVAL", "CURRVAL", "PRAGMA", "FUNCTION", "PROCEDURE");
    private static final Set<String> FUNCTIONS = Set.of("TO_CHAR", "TO_DATE", "TO_TIMESTAMP", "TO_NUMBER",
        "TRUNC", "ROUND", "FLOOR", "CEIL", "ABS", "MOD", "COALESCE", "NVL", "NVL2", "NULLIF",
        "MIN", "MAX", "SUM", "COUNT", "AVG", "GREATEST", "LEAST", "ADD_MONTHS", "LAST_DAY",
        "MONTHS_BETWEEN", "EXTRACT", "CAST", "LOWER", "UPPER", "TRIM", "LTRIM", "RTRIM",
        "SUBSTR", "LENGTH", "REPLACE", "CONCAT", "DECODE", "IN", "EXISTS", "AS", "OVER",
        "SELECT", "AND", "OR", "NOT", "WHEN", "THEN", "ELSE", "FROM", "WHERE", "HAVING",
        "NUMBER", "VARCHAR2", "NVARCHAR2", "CHAR", "NCHAR", "TIMESTAMP");
    private VariableQueryPolicy() { }

    public static String validate(String sql, String dataType) {
        String query = validate(sql);
        if (query.matches("(?is)^SELECT\\s+(SYSDATE|SYSTIMESTAMP|CURRENT_DATE|CURRENT_TIMESTAMP)(\\s*[+-]\\s*[0-9]+(?:\\.[0-9]+)?)?\\s+FROM\\s+DUAL$")
                && !Set.of("DATE", "TIMESTAMP", "STRING").contains(dataType)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "VARIABLE_TYPE_MISMATCH", "A date query requires a DATE or TIMESTAMP parameter (or STRING for textual output).");
        }
        return query;
    }

    public static String validate(String sql) {
        if (sql == null || sql.isBlank() || sql.length() > 20000) throw invalid();
        String query = sql.strip();
        if (query.endsWith(";")) query = query.substring(0, query.length() - 1).stripTrailing();
        var tokens = new ArrayList<String>();
        for (int i = 0; i < query.length();) {
            char c = query.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == '\'' || c == '"') {
                char quote = c; int start = i++; boolean closed = false;
                while (i < query.length()) {
                    if (query.charAt(i++) != quote) continue;
                    if (i < query.length() && query.charAt(i) == quote) { i++; continue; }
                    closed = true; break;
                }
                if (!closed) throw invalid();
                tokens.add(quote == '"' ? query.substring(start, i) : "#literal");
            } else if (c == '-' && i + 1 < query.length() && query.charAt(i + 1) == '-') {
                int end = query.indexOf('\n', i + 2); i = end < 0 ? query.length() : end + 1;
            } else if (c == '/' && i + 1 < query.length() && query.charAt(i + 1) == '*') {
                int end = query.indexOf("*/", i + 2); if (end < 0) throw invalid(); i = end + 2;
            } else if (Character.isLetter(c) || c == '_') {
                int start = i++;
                while (i < query.length() && (Character.isLetterOrDigit(query.charAt(i)) || "_$#".indexOf(query.charAt(i)) >= 0)) i++;
                tokens.add(query.substring(start, i).toUpperCase(Locale.ROOT));
            } else {
                if (";:@?\\".indexOf(c) >= 0) throw invalid();
                tokens.add(String.valueOf(c)); i++;
            }
        }
        if (tokens.isEmpty() || !(tokens.getFirst().equals("SELECT") || tokens.getFirst().equals("WITH"))) throw invalid();
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (FORBIDDEN.contains(token.replace("\"", "").toUpperCase(Locale.ROOT))) throw invalid();
            if (i + 1 < tokens.size() && tokens.get(i + 1).equals("(") &&
                    (Character.isLetter(token.charAt(0)) || token.startsWith("\""))) {
                if (!FUNCTIONS.contains(token) || (i > 0 && tokens.get(i - 1).equals("."))) throw invalid();
            }
        }
        return query;
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "VARIABLE_QUERY_UNSAFE",
            "Use one read-only SELECT returning one value. Only supported built-in functions are allowed; writes, binds, database links and user functions are not allowed.");
    }
}
