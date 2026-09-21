package tr.com.innova.akis.execution;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tr.com.innova.akis.metadata.VariableQueryPolicy;

/** Refreshes a package variable from its pinned binding and records the value in the variable history for the package run. */
@Component
final class PackageVariableEvaluator {
    private final JdbcClient jdbc;
    private final RuntimeOracleConnectionProvider connections;
    private final JdbcProcedureVariableRuntime profiles;

    PackageVariableEvaluator(JdbcClient jdbc, RuntimeOracleConnectionProvider connections, JdbcProcedureVariableRuntime profiles) {
        this.jdbc = jdbc; this.connections = connections; this.profiles = profiles;
    }

    Object refresh(UUID packageRun, JsonNode variable) {
        String type = variable.path("type").asText();
        if (!"REFRESH_QUERY".equals(variable.path("valueSource").asText())) {
            JsonNode value = variable.path("value");
            return value.isMissingNode() || value.isNull() ? null : VariableScalarValue.parse(value.asText(), type);
        }
        UUID project = UUID.fromString(variable.path("projectUuid").asText());
        UUID version = UUID.fromString(variable.path("connectionVersionUuid").asText());
        UUID physical = UUID.fromString(variable.path("physicalSchemaUuid").asText());
        Object value;
        try (var session = connections.openVariable(profiles.profile(project, version, physical), version, variable.path("owner").asText());
             var statement = session.connection().prepareStatement(VariableQueryPolicy.validate(variable.path("query").asText(), type))) {
            statement.setQueryTimeout(30); statement.setMaxRows(2);
            try (var result = statement.executeQuery()) { value = VariableScalarValue.read(result, type); }
        } catch (SQLException failure) {
            throw new IllegalStateException("Değişken tazeleme sorgusu başarısız: " + failure.getMessage(), failure);
        }
        String mode = variable.path("historyMode").asText("LATEST");
        if (!"NONE".equals(mode) && value != null) {
            UUID definition = UUID.fromString(variable.path("definitionUuid").asText());
            UUID environment = UUID.fromString(variable.path("environmentUuid").asText());
            if ("LATEST".equals(mode)) {
                jdbc.sql("delete from akis.degisken_deger_gecmisi where tanim_uuid=:definition and ortam_uuid=:environment and gecmis_modu='LATEST'")
                        .param("definition", definition).param("environment", environment).update();
            }
            jdbc.sql("""
                    insert into akis.degisken_deger_gecmisi(proje_uuid,tanim_uuid,calistirma_uuid,ortam_uuid,mantiksal_sema_uuid,baglanti_surumu_uuid,plan_ozeti,veri_turu,deger,gecmis_modu)
                    values(:project,:definition,:run,:environment,:logical,:version,:hash,:type,:value,:mode)
                    """).param("project", project).param("definition", definition).param("run", packageRun).param("environment", environment)
                    .param("logical", UUID.fromString(variable.path("logicalSchemaUuid").asText())).param("version", version)
                    .param("hash", "0".repeat(64)).param("type", type).param("value", String.valueOf(value)).param("mode", mode).update();
        }
        return value;
    }

    /** ODI "Evaluate Variable": {operator: EQUALS|NOT_EQUALS|GREATER|GREATER_OR_EQUAL|LESS|LESS_OR_EQUAL|IS_NULL|IS_NOT_NULL, value}. Missing spec = TRUE. */
    static boolean evaluate(Object value, JsonNode spec) {
        if (spec == null || !spec.isObject()) return true;
        String operator = spec.path("operator").asText("EQUALS");
        if ("IS_NULL".equals(operator)) return value == null;
        if ("IS_NOT_NULL".equals(operator)) return value != null;
        if (value == null) return false;
        String expected = spec.path("value").asText();
        int comparison;
        if (value instanceof Number number) {
            comparison = new BigDecimal(number.toString()).compareTo(new BigDecimal(expected.trim()));
        } else if (value instanceof Boolean flag) {
            comparison = flag.compareTo(Boolean.parseBoolean(expected.trim()));
        } else {
            comparison = String.valueOf(value).compareTo(expected);
        }
        return switch (operator) {
            case "NOT_EQUALS" -> comparison != 0;
            case "GREATER" -> comparison > 0;
            case "GREATER_OR_EQUAL" -> comparison >= 0;
            case "LESS" -> comparison < 0;
            case "LESS_OR_EQUAL" -> comparison <= 0;
            default -> comparison == 0;
        };
    }
}
