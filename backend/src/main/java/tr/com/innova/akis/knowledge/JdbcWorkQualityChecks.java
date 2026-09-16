package tr.com.innova.akis.knowledge;

import java.sql.*;
import java.util.*;

/** CKM checks against sealed work data. Never returns business values or performs target DML. */
public final class JdbcWorkQualityChecks {
    public enum Rule { NOT_NULL, UNIQUE }
    public record Contract(List<String> requiredColumns, List<List<String>> uniqueKeys) {
        public Contract {
            requiredColumns = identifiers(requiredColumns);
            uniqueKeys = uniqueKeys.stream().map(JdbcWorkQualityChecks::identifiers).toList();
            if (uniqueKeys.size() > 64 || uniqueKeys.stream().anyMatch(List::isEmpty)
                    || uniqueKeys.stream().distinct().count() != uniqueKeys.size())
                throw new IllegalArgumentException("CKM anahtar tanımı geçersiz.");
        }
    }
    public static final class CheckFailure extends RuntimeException {
        private final Rule rule;
        CheckFailure(Rule rule) {
            super(rule == Rule.NOT_NULL ? "CKM: zorunlu kolonlarda boş değer bulundu."
                    : "CKM: benzersiz anahtar ihlali bulundu.");
            this.rule = rule;
        }
        public Rule rule() { return rule; }
    }

    public void verify(Connection connection, JdbcStagingTransfer.Table stage, Contract contract,
            Rule rule, int timeoutSeconds, Runnable ownershipAndLeaseCheck) {
        Objects.requireNonNull(connection); Objects.requireNonNull(contract); Objects.requireNonNull(rule);
        Objects.requireNonNull(ownershipAndLeaseCheck);
        if (timeoutSeconds < 1 || timeoutSeconds > 3600 || !stage.name().startsWith("AKIS_"))
            throw new IllegalArgumentException("CKM çalışma tablosu sözleşmesi geçersiz.");
        // A selected rule without metadata must not be displayed as successfully checked.
        if (rule == Rule.NOT_NULL && contract.requiredColumns().isEmpty()
                || rule == Rule.UNIQUE && contract.uniqueKeys().isEmpty())
            throw new IllegalArgumentException("Seçili CKM kuralı için sabitlenmiş kolon/anahtar bilgisi yok.");
        List<String> queries = new ArrayList<>();
        if (rule == Rule.NOT_NULL) {
            queries.add("SELECT 1 FROM " + stage.sql() + " WHERE ("
                    + String.join(" OR ", contract.requiredColumns().stream().map(c -> quote(c) + " IS NULL").toList())
                    + ") AND ROWNUM=1");
        } else {
            for (List<String> key : contract.uniqueKeys()) {
                String columns = String.join(",", key.stream().map(JdbcWorkQualityChecks::quote).toList());
                // Oracle permits an all-NULL composite unique key. Partly NULL duplicate keys still conflict.
                String nonNull = String.join(" OR ", key.stream().map(c -> quote(c) + " IS NOT NULL").toList());
                queries.add("SELECT 1 FROM (SELECT " + columns + " FROM " + stage.sql()
                        + " WHERE " + nonNull + " GROUP BY " + columns + " HAVING COUNT(*)>1) WHERE ROWNUM=1");
            }
        }
        for (String query : queries) {
            ownershipAndLeaseCheck.run();
            try (PreparedStatement statement = connection.prepareStatement(query)) {
                statement.setQueryTimeout(timeoutSeconds);
                statement.setMaxRows(1);
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next()) throw new CheckFailure(rule);
                }
            } catch (SQLException failure) {
                throw new IllegalStateException("CKM kontrolü tamamlanamadı; hedef yayınlanamaz.");
            }
            ownershipAndLeaseCheck.run();
        }
    }

    private static List<String> identifiers(List<String> names) {
        names = List.copyOf(names);
        if (names.size() > 256 || names.stream().distinct().count() != names.size())
            throw new IllegalArgumentException("CKM kolon tanımı geçersiz.");
        names.forEach(StagedMappingDefinition::identifier);
        return names;
    }
    private static String quote(String name) { return "\"" + StagedMappingDefinition.identifier(name) + "\""; }
}
