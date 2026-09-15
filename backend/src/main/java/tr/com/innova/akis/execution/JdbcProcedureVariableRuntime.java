package tr.com.innova.akis.execution;

import java.sql.SQLException;
import java.sql.Timestamp;
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
                if (existing.isPresent()) return Timestamp.valueOf(existing.get());
                UUID version = UUID.fromString(binding.path("connectionVersionUuid").asText());
                ConnectionProfile profile = profile(project, version, UUID.fromString(binding.path("physicalSchemaUuid").asText()));
                Timestamp value;
                try (var session = connections.openVariable(profile, version);
                     var statement = session.connection().prepareStatement(parameter.refreshQuery())) {
                    statement.setQueryTimeout(30); statement.setMaxRows(2);
                    try (var result = statement.executeQuery()) {
                        if (result.getMetaData().getColumnCount() != 1 || !result.next()) throw new SQLException("Expected one value");
                        value = result.getTimestamp(1);
                        if (value == null || result.next()) throw new SQLException("Expected exactly one non-null value");
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
            select bs.*, bk.gizli_deger_saglayicisi, bk.gizli_deger_konumu
              from akis.baglanti_surumu bs join akis.proje p on p.id=bs.proje_id
              join akis.baglanti b on b.id=bs.baglanti_id and b.proje_id=p.id
              join akis.fiziksel_sema fs on fs.baglanti_id=b.id and fs.proje_id=p.id
              join akis.baglanti_kimligi bk on bk.baglanti_surumu_id=bs.id and bk.proje_id=p.id and bk.kullanim_amaci='VERITABANI'
             where p.uuid=:project and bs.uuid=:version and fs.uuid=:physical
               and bs.durum='ETKIN' and b.saglayici_turu='ORACLE' and b.arsivlenme_zamani is null and fs.arsivlenme_zamani is null
            """).param("project", project).param("version", version).param("physical", physical).query((rs, n) -> {
                var policy = mapper.createObjectNode().put("connectTimeoutMs", rs.getInt("baglanti_zaman_asimi_ms"))
                    .put("readTimeoutMs", rs.getInt("okuma_zaman_asimi_ms")).put("networkTimeoutMs", rs.getInt("ag_zaman_asimi_ms"))
                    .put("queryTimeoutSeconds", rs.getInt("sorgu_zaman_asimi_saniye"));
                return new ConnectionProfile(project, version, rs.getString("baglanti_modu"), rs.getString("jndi_adi"),
                    rs.getString("surucu_sinifi"), rs.getString("sunucu_adi"), rs.getString("servis_adi"), rs.getString("sid"),
                    rs.getInt("port"), switch (rs.getString("tls_modu")) { case "ZORUNLU" -> "REQUIRED"; case "DEVRE_DISI" -> "DISABLED"; default -> rs.getString("tls_modu"); }, policy,
                    rs.getString("gizli_deger_saglayicisi"), rs.getString("gizli_deger_konumu"));
            }).single();
    }
}
