package tr.com.innova.akis.publication;

import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tr.com.innova.akis.knowledge.AkisKmTemplate;
import tr.com.innova.akis.knowledge.JdbcStagingTransfer;
import tr.com.innova.akis.knowledge.StagedMappingDefinition;

/**
 * Renders the statements the KM runtime will issue, for the pre-run report only. Nothing here touches a source or
 * target database: work-table names are run-specific, so the loading name is shown as a placeholder.
 */
public final class StagedSqlPreview {
    private StagedSqlPreview() { }

    static ArrayNode render(ObjectMapper mapper, JdbcClient jdbc, StagedMappingDefinition definition, JsonNode plan) {
        return render(mapper, jdbc, definition, plan, null);
    }

    /** Runtime evidence uses the resolved per-run work-table name instead of the design-time placeholder. */
    public static ArrayNode renderRuntime(ObjectMapper mapper, JdbcClient jdbc, StagedMappingDefinition definition, JsonNode plan, String workName) {
        return render(mapper, jdbc, definition, plan, Objects.requireNonNull(workName));
    }

    private static ArrayNode render(ObjectMapper mapper, JdbcClient jdbc, StagedMappingDefinition definition, JsonNode plan, String resolvedWorkName) {
        ArrayNode statements = mapper.createArrayNode();
        var bindings = new LinkedHashMap<String, JsonNode>();
        plan.path("bindings").forEach(binding -> bindings.put(binding.path("nodeCode").asText(), binding));
        JsonNode target = bindings.values().stream().filter(b -> "HEDEF".equals(b.path("role").asText())).findFirst().orElse(null);
        if (target == null) return statements;
        String stagingOwner = plan.path("staging").path("owner").asText();
        String loadingPrefix = plan.path("staging").path("prefixes").path("loading").asText("C$_");
        String marker = loadingPrefix.endsWith("_") ? loadingPrefix : loadingPrefix + "_";
        String workName = resolvedWorkName == null ? "AKIS_" + marker + "RUN_HASH" : resolvedWorkName;
        String workTable = quote(stagingOwner) + "." + quote(workName);
        String targetTable = quote(target.path("owner").asText()) + "." + quote(target.path("objectName").asText());

        // Target column types come from the pinned target snapshot; the same DDL derivation as StagedColumnLayout.
        Map<String, String> ddlTypes = new LinkedHashMap<>();
        Map<String, JdbcStagingTransfer.Type> transferTypes = new LinkedHashMap<>();
        jdbc.sql("""
                select kg.kolon_referansi, kg.uretici_tipi, kg.hassasiyet, kg.olcek, kg.uzunluk, kg.zaman_hassasiyeti
                  from akis.kolon_goruntusu kg join akis.sema_goruntusu sg on sg.id = kg.sema_goruntusu_id
                 where sg.uuid = :snapshot order by kg.sira_no, kg.kolon_referansi
                """).param("snapshot", UUID.fromString(target.path("schemaSnapshotUuid").asText())).query(rs -> {
            String type = rs.getString("uretici_tipi").toUpperCase(Locale.ROOT).replaceAll("\\(.*", "").strip();
            Integer precision = rs.getObject("hassasiyet", Integer.class); Integer scale = rs.getObject("olcek", Integer.class);
            Long length = rs.getObject("uzunluk", Long.class); Integer timePrecision = rs.getObject("zaman_hassasiyeti", Integer.class);
            String ddl = switch (type) {
                case "NUMBER" -> precision == null ? "NUMBER" : "NUMBER(" + precision + "," + (scale == null ? 0 : scale) + ")";
                case "VARCHAR2" -> "VARCHAR2(" + length + " CHAR)";
                case "NVARCHAR2" -> "NVARCHAR2(" + length + ")";
                case "NUMERIC", "DECIMAL" -> precision == null ? "NUMERIC" : "NUMERIC(" + precision + "," + (scale == null ? 0 : scale) + ")";
                case "VARCHAR", "CHARACTER VARYING" -> length == null ? null : "VARCHAR(" + length + ")";
                case "TEXT", "SMALLINT", "INTEGER", "BIGINT" -> type;
                case "DATE" -> "DATE";
                case "TIMESTAMP", "TIMESTAMP WITHOUT TIME ZONE" -> "TIMESTAMP(" + (timePrecision == null ? 6 : timePrecision) + ")";
                default -> null;
            };
            ddlTypes.put(rs.getString("kolon_referansi"), ddl);
            JdbcStagingTransfer.Type transfer = switch (type) {
                case "NUMERIC", "DECIMAL", "SMALLINT", "INTEGER", "BIGINT" -> JdbcStagingTransfer.Type.NUMBER;
                case "VARCHAR", "CHARACTER VARYING", "TEXT" -> JdbcStagingTransfer.Type.VARCHAR2;
                case "TIMESTAMP WITHOUT TIME ZONE" -> JdbcStagingTransfer.Type.TIMESTAMP;
                default -> { try { yield JdbcStagingTransfer.Type.valueOf(type); } catch (IllegalArgumentException unsupported) { yield null; } }
            };
            if (transfer != null) transferTypes.put(rs.getString("kolon_referansi"), transfer);
        });

        List<JdbcStagingTransfer.Column> columns = new ArrayList<>();
        List<String> ddlColumns = new ArrayList<>();
        List<String> unsupported = new ArrayList<>();
        for (JsonNode mapping : plan.path("columns")) {
            String targetColumn = mapping.path("target").path("column").asText();
            String ddl = ddlTypes.get(targetColumn);
            var type = transferTypes.get(targetColumn);
            if (ddl == null || type == null) { unsupported.add(targetColumn); continue; }
            ddlColumns.add(quote(targetColumn) + " " + ddl);
            columns.add(mapping.hasNonNull("expression")
                    ? new JdbcStagingTransfer.Column(null, null, targetColumn, type, mapping.path("expression"))
                    : new JdbcStagingTransfer.Column(mapping.path("source").path("object").asText(), mapping.path("source").path("column").asText(), targetColumn, type));
        }

        add(statements, "CREATE_WORK", "STAGING", stagingOwner, "CREATE TABLE " + workTable + " (\n\t" + String.join(",\n\t", ddlColumns) + "\n)");

        List<JdbcStagingTransfer.QuerySource> sources = definition.sources().stream().map(reference -> {
            JsonNode binding = bindings.get(reference.id());
            var table = new JdbcStagingTransfer.Table(binding.path("owner").asText(), binding.path("objectName").asText());
            return new JdbcStagingTransfer.QuerySource(reference.id(), reference.alias(), table, Set.of());
        }).toList();
        var options = new JdbcStagingTransfer.QueryOptions(definition.booleanOption("loading", "DISTINCT"), definition.stringOption("loading", "ORACLE_HINT"), sources, definition.joins(), definition.filters());
        try {
            var preview = JdbcStagingTransfer.previewSql(sources.isEmpty() ? null : sources.get(0).table(), new JdbcStagingTransfer.Table(stagingOwner, workName), columns, options);
            String sourceOwner = sources.isEmpty() ? "" : sources.get(0).table().owner();
            add(statements, "TRANSFER_JDBC", "SOURCE", sourceOwner, prettySelect(preview.select()));
            add(statements, "TRANSFER_JDBC", "STAGING", stagingOwner, "INSERT INTO " + workTable + " (\n\t"
                    + String.join(",\n\t", columns.stream().map(c -> quote(c.stage())).toList()) + "\n)\nVALUES (\n\t"
                    + String.join(",\n\t", columns.stream().map(c -> ":" + c.stage()).toList()) + "\n)");
        } catch (IllegalArgumentException failure) {
            add(statements, "TRANSFER_JDBC", "SOURCE", "", "-- " + failure.getMessage());
        }
        add(statements, "SEAL_WORK", "STAGING", stagingOwner, "SELECT COUNT(*) FROM " + workTable + " -- satır sayısı ve içerik özeti (SHA-256) mühürlenir");

        var integration = plan.path("modules").path("integration").path("options");
        String mode = integration.path("WRITE_MODE").asText("ATOMIC_DELETE_INSERT");
        String hint = integration.path("ORACLE_HINT").asText("").isBlank() ? "" : " /*+ " + integration.path("ORACLE_HINT").asText() + " */";
        String targetColumns = String.join(",\n\t", columns.stream().map(c -> quote(c.stage())).toList());
        String targetOwner = target.path("owner").asText();
        add(statements, "ATOMIC_REPLACE", "TARGET", targetOwner, "LOCK TABLE " + targetTable + " IN EXCLUSIVE MODE NOWAIT");
        add(statements, "ATOMIC_REPLACE", "TARGET", targetOwner, "LOCK TABLE " + workTable + " IN SHARE MODE NOWAIT");
        if ("TRUNCATE_LOAD".equals(mode)) add(statements, "ATOMIC_REPLACE", "TARGET", targetOwner, "TRUNCATE TABLE " + targetTable + " -- geri alınamaz DDL (örtük commit)");
        if ("ATOMIC_DELETE_INSERT".equals(mode)) add(statements, "ATOMIC_REPLACE", "TARGET", targetOwner, "DELETE FROM " + targetTable);
        if ("MERGE".equals(mode)) {
            List<String> keys = new ArrayList<>();
            JsonNode keyColumns = integration.path("KEY_COLUMNS");
            if (keyColumns.isArray()) keyColumns.forEach(key -> keys.add(key.asText()));
            else if (keyColumns.isTextual()) Arrays.stream(keyColumns.asText().split(","))
                    .map(String::strip).filter(key -> !key.isEmpty()).forEach(keys::add);
            String on = keys.isEmpty() ? "<KEY_COLUMNS>" : String.join("\n\tAND ", keys.stream().map(key -> "T." + quote(key) + " = S." + quote(key)).toList());
            List<String> mutable = columns.stream().map(JdbcStagingTransfer.Column::stage).filter(name -> !keys.contains(name)).toList();
            String update = mutable.isEmpty() ? "" : "\nWHEN MATCHED THEN UPDATE SET\n\t" + String.join(",\n\t", mutable.stream().map(name -> "T." + quote(name) + " = S." + quote(name)).toList());
            add(statements, "ATOMIC_REPLACE", "TARGET", targetOwner, "MERGE" + hint + " INTO " + targetTable + " T\nUSING " + workTable + " S\nON (" + on + ")" + update
                    + "\nWHEN NOT MATCHED THEN INSERT (\n\t" + targetColumns + "\n)\nVALUES (\n\t" + String.join(",\n\t", columns.stream().map(c -> "S." + quote(c.stage())).toList()) + "\n)");
        } else {
            add(statements, "ATOMIC_REPLACE", "TARGET", targetOwner, "INSERT" + hint + " INTO " + targetTable + " (\n\t" + targetColumns + "\n)\nSELECT\n\t" + targetColumns + "\nFROM " + workTable);
        }
        add(statements, "ATOMIC_REPLACE", "TARGET", targetOwner, "COMMIT");
        boolean dropWork = false;
        for (JsonNode step : plan.path("steps")) if ("DROP_WORK".equals(step.path("operation").asText())) dropWork = true;
        if (dropWork) add(statements, "DROP_WORK", "STAGING", stagingOwner, "DROP TABLE " + workTable + " -- hedef yazımı başarıyla tamamlandıktan sonra");
        if (!unsupported.isEmpty()) add(statements, "CREATE_WORK", "STAGING", stagingOwner, "-- Desteklenmeyen hedef kolon tipi: " + String.join(", ", unsupported));
        return compileKm3(mapper, plan, statements, workTable, targetTable, ddlColumns, columns);
    }

    /**
     * AKIS_KM/3 turns the pinned command templates into immutable SQL evidence. Older publications deliberately keep
     * using the finite built-in statement generator above. Operations are still executed by trusted runtime adapters;
     * templates describe and pin the exact SQL shape, they never become an arbitrary script engine.
     */
    private static ArrayNode compileKm3(ObjectMapper mapper, JsonNode plan, ArrayNode baseline, String workTable,
            String targetTable, List<String> ddlColumns, List<JdbcStagingTransfer.Column> columns) {
        Map<String, List<JsonNode>> commands = new LinkedHashMap<>();
        Map<String, JsonNode> moduleByStep = new HashMap<>();
        plan.path("modules").properties().forEach(entry -> entry.getValue().path("commands").forEach(command -> {
            commands.computeIfAbsent(command.path("stepId").asText(), ignored -> new ArrayList<>()).add(command);
            moduleByStep.put(command.path("stepId").asText(), entry.getValue());
        }));
        if (commands.isEmpty()) return baseline;

        Map<String, List<JsonNode>> baselineByOperation = new LinkedHashMap<>();
        baseline.forEach(statement -> baselineByOperation.computeIfAbsent(statement.path("step").asText(), ignored -> new ArrayList<>()).add(statement));
        ArrayNode compiled = mapper.createArrayNode();
        for (JsonNode step : plan.path("steps")) {
            String stepId = step.path("id").asText();
            String operation = step.path("operation").asText();
            List<JsonNode> templates = commands.get(stepId);
            if (templates == null || templates.isEmpty()) {
                baselineByOperation.getOrDefault(operation, List.of()).forEach(statement -> compiled.add(statement.deepCopy()));
                continue;
            }
            JsonNode module = moduleByStep.get(stepId);
            for (JsonNode command : templates) {
                String channel = command.path("channel").asText();
                String site = switch (channel) {
                    case "SOURCE_SQL" -> "SOURCE";
                    case "TARGET_SQL" -> "STAGING";
                    default -> step.path("site").asText();
                };
                String owner = ownerFor(site, baselineByOperation.getOrDefault(operation, List.of()));
                String sql = AkisKmTemplate.render(command.path("template").asText(), (function, arguments) -> switch (function) {
                    case "table" -> table(arguments, workTable, targetTable);
                    case "columns" -> columns(arguments, ddlColumns, columns);
                    case "context" -> context(arguments, baselineByOperation.getOrDefault(operation, List.of()));
                    case "integration" -> integration(arguments, baselineByOperation.getOrDefault(operation, List.of()));
                    case "option" -> option(arguments, module.path("options"));
                    case "tables", "filters" -> throw new IllegalArgumentException("Bu akisRef fonksiyonu henüz bu KM bağlamında desteklenmiyor: " + function);
                    case "check" -> check(arguments, workTable);
                    default -> throw new IllegalArgumentException("Desteklenmeyen akisRef fonksiyonu: " + function);
                });
                add(compiled, operation, site, owner, sql.strip());
            }
        }
        return compiled;
    }

    private static String ownerFor(String site, List<JsonNode> statements) {
        return statements.stream().filter(node -> site.equals(node.path("site").asText())).map(node -> node.path("owner").asText()).findFirst()
                .orElseGet(() -> statements.stream().map(node -> node.path("owner").asText()).findFirst().orElse(""));
    }

    private static String table(List<String> arguments, String workTable, String targetTable) {
        requireArguments("table", arguments, 2);
        if (!"QUALIFIED".equals(arguments.get(1))) throw new IllegalArgumentException("table yalnız QUALIFIED biçimini destekler.");
        return switch (arguments.get(0)) { case "WORK" -> workTable; case "TARGET" -> targetTable; default -> throw new IllegalArgumentException("Bilinmeyen tablo rolü: " + arguments.get(0)); };
    }

    private static String columns(List<String> arguments, List<String> ddlColumns, List<JdbcStagingTransfer.Column> columns) {
        requireArguments("columns", arguments, 3);
        if (!"TARGET".equals(arguments.get(0))) throw new IllegalArgumentException("columns yalnız TARGET rolünü destekler.");
        String separator = arguments.get(2);
        return switch (arguments.get(1)) {
            case "DDL" -> String.join(separator, ddlColumns);
            case "NAME" -> String.join(separator, columns.stream().map(column -> quote(column.stage())).toList());
            case "BIND" -> String.join(separator, columns.stream().map(column -> ":" + column.stage()).toList());
            default -> throw new IllegalArgumentException("Bilinmeyen kolon görünümü: " + arguments.get(1));
        };
    }

    private static String context(List<String> arguments, List<JsonNode> statements) {
        requireArguments("context", arguments, 1);
        String site = switch (arguments.get(0)) { case "SOURCE_SELECT" -> "SOURCE"; case "STAGING_INSERT" -> "STAGING"; default -> throw new IllegalArgumentException("Bilinmeyen çalışma bağlamı: " + arguments.get(0)); };
        return statements.stream().filter(node -> site.equals(node.path("site").asText())).map(node -> node.path("sql").asText()).findFirst()
                .orElseGet(() -> statements.stream().map(node -> node.path("sql").asText()).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("KM çalışma bağlamı üretilemedi: " + arguments.get(0))));
    }

    private static String integration(List<String> arguments, List<JsonNode> statements) {
        requireArguments("integration", arguments, 1);
        if (!"WRITE_MODE".equals(arguments.get(0))) throw new IllegalArgumentException("Bilinmeyen entegrasyon bağlamı: " + arguments.get(0));
        if (statements.isEmpty()) throw new IllegalArgumentException("Entegrasyon SQL'i üretilemedi.");
        return String.join(";\n", statements.stream().map(node -> node.path("sql").asText()).toList());
    }

    private static String option(List<String> arguments, JsonNode options) {
        requireArguments("option", arguments, 1);
        JsonNode value = options.path(arguments.get(0));
        if (value.isMissingNode() || value.isNull()) return "";
        if (!value.isValueNode()) throw new IllegalArgumentException("KM seçeneği skaler olmalıdır: " + arguments.get(0));
        return value.asText();
    }

    private static String check(List<String> arguments, String workTable) {
        requireArguments("check", arguments, 1);
        return switch (arguments.get(0)) {
            case "NOT_NULL" -> "SELECT COUNT(*) AS violation_count FROM " + workTable + " /* sabitlenmiş zorunlu kolon sözleşmesi runtime tarafından uygulanır */";
            case "UNIQUE" -> "SELECT COUNT(*) AS violation_count FROM " + workTable + " /* sabitlenmiş benzersiz anahtar sözleşmesi runtime tarafından uygulanır */";
            default -> throw new IllegalArgumentException("Bilinmeyen kalite kontrolü: " + arguments.get(0));
        };
    }

    private static void requireArguments(String function, List<String> arguments, int count) {
        if (arguments.size() != count) throw new IllegalArgumentException(function + " " + count + " argüman bekler.");
    }

    private static void add(ArrayNode statements, String step, String site, String owner, String sql) {
        var node = statements.addObject();
        node.put("step", step); node.put("site", site); node.put("owner", owner); node.put("sql", sql);
    }
    /** Identifiers are validated uppercase Oracle names, so the report shows them unquoted; the runtime quotes them, which is equivalent. */
    private static String quote(String name) { return StagedMappingDefinition.identifier(name); }

    /** Line-per-projection layout for the runtime SELECT: splits on top-level commas only, so function arguments stay together. */
    static String prettySelect(String sql) {
        String text = sql.replace("\"", "");
        StringBuilder out = new StringBuilder();
        int depth = 0; boolean inLiteral = false;
        String[] keywords = {" FROM ", " WHERE ", " LEFT JOIN ", " RIGHT JOIN ", " FULL OUTER JOIN ", " JOIN ", " AND ", " ON "};
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'') inLiteral = !inLiteral;
            if (!inLiteral) {
                if (c == '(') depth++; else if (c == ')') depth--;
                if (c == ',' && depth == 0) { out.append(",\n\t"); continue; }
                if (depth == 0) {
                    String matched = null;
                    for (String keyword : keywords) if (text.startsWith(keyword, i)) { matched = keyword; break; }
                    if (matched != null) {
                        String word = matched.strip();
                        out.append(word.equals("AND") || word.equals("ON") ? "\n\t" + word + " " : "\n" + word + " ");
                        i += matched.length() - 1;
                        continue;
                    }
                }
            }
            out.append(c);
        }
        return out.toString().replaceFirst("^SELECT DISTINCT ", "SELECT DISTINCT\n\t").replaceFirst("^SELECT ", "SELECT\n\t");
    }
}
