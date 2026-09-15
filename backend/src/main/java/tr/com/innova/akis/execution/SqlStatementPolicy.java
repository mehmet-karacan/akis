package tr.com.innova.akis.execution;

import java.util.List;
import tr.com.innova.akis.metadata.NamedBindParser;

/** Static policy diagnostics, not Oracle parsing and never SQL execution. */
public final class SqlStatementPolicy {
    public static final int POLICY_VERSION = 1;
    private SqlStatementPolicy() { }

    public record Diagnostic(int policyVersion, String type, String riskClass,
            boolean requiresApproval, List<String> binds) { }

    public static Diagnostic inspect(String command, String role) {
        if (command == null || command.isBlank() || command.length() > 65_536)
            throw new IllegalArgumentException("SQL must contain between 1 and 65536 characters.");
        var compiled = NamedBindParser.compile(command);
        String sql = compiled.executableSql();
        String keyword = sql.split("[^A-Z]", 2)[0];
        String type = switch (keyword) {
            case "BEGIN", "DECLARE" -> "PLSQL";
            case "CALL" -> "STORED_PROCEDURE";
            default -> "SQL";
        };
        String risk = switch (keyword) {
            case "SELECT" -> "READ_ONLY";
            case "INSERT", "UPDATE", "MERGE" -> "DML";
            case "CREATE", "ALTER", "COMMENT", "GRANT", "REVOKE" -> "DDL";
            case "TRUNCATE", "DROP", "DELETE", "BEGIN", "DECLARE", "CALL" -> "DESTRUCTIVE";
            default -> throw new IllegalArgumentException("SQL command is outside the supported policy.");
        };
        if (!("SOURCE".equals(role) || "TARGET".equals(role))
                || ("SOURCE".equals(role) && !"SELECT".equals(keyword)))
            throw new IllegalArgumentException("Source commands must be read-only SELECT statements.");
        if ("SQL".equals(type) && sql.contains(";"))
            throw new IllegalArgumentException("Use a single SQL statement without a terminator.");
        int depth = 0;
        for (char ch : sql.toCharArray()) {
            if (ch == '(') depth++;
            else if (ch == ')' && --depth < 0) throw new IllegalArgumentException("Unbalanced parentheses.");
        }
        if (depth != 0 || java.util.regex.Pattern.compile("(,\\s*[,)]|\\(\\s*,|,\\s*FROM\\b)").matcher(sql).find())
            throw new IllegalArgumentException("Check parentheses and comma placement.");
        return new Diagnostic(POLICY_VERSION, type, risk,
                "DESTRUCTIVE".equals(risk) || "DDL".equals(risk), compiled.names());
    }
}
