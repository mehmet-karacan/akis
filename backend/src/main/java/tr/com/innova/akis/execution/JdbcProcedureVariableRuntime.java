package tr.com.innova.akis.execution;

import java.sql.SQLException;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import tr.com.innova.akis.execution.RuntimeOracleConnectionMetadataPort.ConnectionProfile;

/** Refreshes on the published variable connection, never on a consuming command connection. */
@Component
final class JdbcProcedureVariableRuntime {
    private final JdbcClient jdbc;
    private final RuntimeOracleConnectionProvider connections;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;

    JdbcProcedureVariableRuntime(JdbcClient jdbc, RuntimeOracleConnectionProvider connections,
            ObjectMapper mapper, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.connections = connections; this.mapper = mapper;
        this.transaction = new TransactionTemplate(manager);
    }

    Object resolve(UUID run, ProcedureRuntimePlan plan, ProcedureRuntimePlan.ParameterValue parameter) throws SQLException {
        JsonNode binding = plan.canonicalPlan().path("variableBindings").path(String.valueOf(parameter.definitionUuid()));
        if (!binding.isObject() || parameter.logicalSchemaUuid() == null
                || !parameter.logicalSchemaUuid().toString().equals(binding.path("logicalSchemaUuid").asText())
                || !parameter.refreshQuery().equals(binding.path("query").asText())
                || !parameter.type().name().equals(binding.path("type").asText())
                || !parameter.historyMode().equals(binding.path("historyMode").asText())) {
            throw new SQLException("Variable has no matching published logical schema binding. Republish the procedure.");
        }
        try {
            return transaction.execute(status -> {
                UUID project = UUID.fromString(binding.path("projectUuid").asText());
                UUID environment = UUID.fromString(binding.path("environmentUuid").asText());
                // Serialize refresh and retention for one variable. Also prevents duplicate run snapshots.
                jdbc.sql("select uuid from akis.tanim where uuid=:definition and proje_id=(select id from akis.proje where uuid=:project) for update")
                    .param("definition", parameter.definitionUuid()).param("project", project).query(UUID.class).single();
                var existing = jdbc.sql("select deger from akis.degisken_deger_gecmisi where calistirma_uuid=:run and tanim_uuid=:definition and plan_ozeti=:hash")
                    .param("run", run).param("definition", parameter.definitionUuid()).param("hash", plan.runtimePlanHash()).query(String.class).optional();
                if (existing.isPresent()) return VariableScalarValue.parse(existing.get(), parameter.type().name());
                UUID version = UUID.fromString(binding.path("connectionVersionUuid").asText());
                ConnectionProfile profile = profile(project, version, UUID.fromString(binding.path("physicalSchemaUuid").asText()));
                Object value;
                try (var session = connections.openVariable(profile, version, binding.path("owner").asText());
                     var statement = session.connection().prepareStatement(tr.com.innova.akis.metadata.VariableQueryPolicy.validate(parameter.refreshQuery(), parameter.type().name()))) {
                    statement.setQueryTimeout(30); statement.setMaxRows(2);
                    try (var result = statement.executeQuery()) {
                        value = VariableScalarValue.read(result, parameter.type().name());
                    }
                } catch (SQLException error) { throw new IllegalStateException("Variable refresh failed", error); }
                if (!"NONE".equals(parameter.historyMode())) {
                    if ("LATEST".equals(parameter.historyMode())) {
                        jdbc.sql("delete from akis.degisken_deger_gecmisi where tanim_uuid=:definition and ortam_uuid=:environment and gecmis_modu='LATEST'")
                            .param("definition", parameter.definitionUuid()).param("environment", environment).update();
                    }
                    jdbc.sql("""
                        insert into akis.degisken_deger_gecmisi(proje_uuid,tanim_uuid,calistirma_uuid,ortam_uuid,
                            mantiksal_sema_uuid,baglanti_surumu_uuid,plan_ozeti,veri_turu,deger,gecmis_modu)
                        values(:project,:definition,:run,:environment,:logical,:version,:hash,:type,:value,:mode)
                        """).param("project", project).param("definition", parameter.definitionUuid()).param("run", run)
                        .param("environment", environment).param("logical", parameter.logicalSchemaUuid()).param("version", version)
                        .param("hash", plan.runtimePlanHash()).param("type", parameter.type().name()).param("value", value.toString())
                        .param("mode", parameter.historyMode()).update();
                }
                return value;
            });
        } catch (RuntimeException failure) { throw new SQLException("Variable refresh or history persistence failed.", failure); }
    }

    ConnectionProfile profile(UUID project, UUID version, UUID physical) {
        return jdbc.sql("""
            select b.baglanti_modu, b.jndi_adi, b.surucu_sinifi, b.sunucu_adi,
                   case when b.saglayici_turu = 'ORACLE' then b.servis_adi else b.veritabani_adi end as servis_adi,
                   b.sid, b.port, b.baglanti_zaman_asimi_ms, b.okuma_zaman_asimi_ms, b.sorgu_zaman_asimi_saniye, b.sifre
              from akis.baglanti b
              join akis.fiziksel_sema fs on fs.baglanti_id=b.id
             where b.uuid=:version and fs.uuid=:physical
               and b.durum='ETKIN' and fs.durum='ETKIN' and b.saglayici_turu in ('ORACLE','POSTGRESQL')
            """).param("version", version).param("physical", physical).query((rs, n) -> {
                var policy = mapper.createObjectNode().put("connectTimeoutMs", rs.getInt("baglanti_zaman_asimi_ms"))
                    .put("readTimeoutMs", rs.getInt("okuma_zaman_asimi_ms")).put("networkTimeoutMs", rs.getInt("okuma_zaman_asimi_ms"))
                    .put("queryTimeoutSeconds", rs.getInt("sorgu_zaman_asimi_saniye"));
                return new ConnectionProfile(project, version, rs.getString("baglanti_modu"), rs.getString("jndi_adi"),
                    rs.getString("surucu_sinifi"), rs.getString("sunucu_adi"), rs.getString("servis_adi"), rs.getString("sid"),
                    rs.getInt("port"), "DISABLED", policy, "TABLO", rs.getString("sifre"));
            }).single();
    }
}
