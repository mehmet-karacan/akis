package tr.com.innova.akis.execution;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tr.com.innova.akis.metadata.ApiException;
import tr.com.innova.akis.metadata.VariableQueryPolicy;

@Service
final class VariableTestService {
    record Binding(UUID version, UUID physical, String owner) { }
    record Result(long id, String kind, boolean success, String value, String dataType, long durationMs,
                  String errorCode, String environment, String logicalSchema, OffsetDateTime createdAt) { }
    private final JdbcClient jdbc;
    private final JdbcProcedureVariableRuntime profiles;
    private final RuntimeOracleConnectionProvider connections;
    VariableTestService(JdbcClient jdbc, JdbcProcedureVariableRuntime profiles, RuntimeOracleConnectionProvider connections) {
        this.jdbc = jdbc; this.profiles = profiles; this.connections = connections;
    }
    Result test(UUID project, UUID definition, VariableTestController.Request request) {
        String query = VariableQueryPolicy.validate(request.query(), request.dataType());
        if (!Set.of("DATE", "TIMESTAMP", "STRING", "INTEGER", "DECIMAL", "BOOLEAN").contains(request.dataType())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "VARIABLE_TYPE_UNSUPPORTED", "Unsupported variable type.");
        }
        Binding binding = jdbc.sql("""
            select b.uuid as version_uuid, fs.uuid as physical_uuid, fs.sema_adi
              from akis.proje p
              join akis.tanim t on t.proje_id=p.id and t.uuid=:definition and t.tur='DEGISKEN'
              join akis.ortam o on o.uuid=:environment
              join akis.mantiksal_sema ms on ms.uuid=:logical
              join akis.sema_eslemesi se on se.mantiksal_sema_id=ms.id and se.ortam_id=o.id
              join akis.fiziksel_sema fs on fs.id=se.fiziksel_sema_id
              join akis.baglanti b on b.id=fs.baglanti_id
             where p.uuid=:project and p.arsivlenme_zamani is null and t.arsivlenme_zamani is null
               and o.durum='ETKIN' and ms.durum='ETKIN' and fs.durum='ETKIN'
               and b.durum='ETKIN' and b.saglayici_turu in ('ORACLE','POSTGRESQL')
            """).param("project", project).param("definition", definition).param("environment", request.environmentUuid())
            .param("logical", request.logicalSchemaUuid()).query((rs,n) -> new Binding(rs.getObject("version_uuid", UUID.class),
                rs.getObject("physical_uuid", UUID.class), rs.getString("sema_adi"))).optional()
            .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "VARIABLE_MAPPING_REQUIRED",
                "No active Oracle connection is mapped to this variable's logical schema in the selected environment."));
        long start = System.nanoTime(); String value = null; String error = null;
        try (var session = connections.openVariable(profiles.profile(project, binding.version(), binding.physical()), binding.version(), binding.owner());
             var statement = session.connection().prepareStatement(query)) {
            statement.setMaxRows(2);
            try (var rows = statement.executeQuery()) { value = VariableScalarValue.read(rows, request.dataType()).toString(); }
        } catch (RuntimeOracleConnectionException failure) {
            error = failure.vendorCode() != null ? "ORACLE_" + failure.vendorCode() : "VARIABLE_" + failure.failure().name();
        } catch (SQLException failure) {
            error = failure.getErrorCode() > 0 ? "ORACLE_" + failure.getErrorCode() : "VARIABLE_RESULT_INVALID";
        } catch (RuntimeException failure) { error = "VARIABLE_CONNECTION_FAILED"; }
        long duration = Math.max(0, (System.nanoTime() - start) / 1_000_000);
        long id = jdbc.sql("""
            insert into akis.degisken_test_gecmisi(proje_uuid,tanim_uuid,ortam_uuid,mantiksal_sema_uuid,
                baglanti_surumu_uuid,sorgu_ozeti,veri_turu,deger,basarili,hata_kodu,sure_ms)
            values(:project,:definition,:environment,:logical,:version,:hash,:type,:value,:success,:error,:duration) returning id
            """).param("project",project).param("definition",definition).param("environment",request.environmentUuid())
            .param("logical",request.logicalSchemaUuid()).param("version",binding.version()).param("hash",hash(query))
            .param("type",request.dataType()).param("value",error == null ? value : null).param("success",error == null)
            .param("error",error).param("duration",duration).query(Long.class).single();
        return history(project, definition, id + 1).stream().filter(row -> row.id() == id).findFirst().orElseThrow();
    }
    List<Result> history(UUID project, UUID definition, long before) {
        return jdbc.sql("""
            select h.*, o.ad as environment_name, ms.ad as logical_name from akis.degisken_test_gecmisi h
              join akis.ortam o on o.uuid=h.ortam_uuid join akis.mantiksal_sema ms on ms.uuid=h.mantiksal_sema_uuid
             where h.proje_uuid=:project and h.tanim_uuid=:definition and h.id<:before order by h.id desc limit 50
            """).param("project",project).param("definition",definition).param("before",before)
            .query((rs,n) -> new Result(rs.getLong("id"), "TEST", rs.getBoolean("basarili"), rs.getString("deger"),
                rs.getString("veri_turu"), rs.getLong("sure_ms"), rs.getString("hata_kodu"), rs.getString("environment_name"),
                rs.getString("logical_name"), rs.getObject("olusturulma_zamani",OffsetDateTime.class))).list();
    }
    private static String hash(String query) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(query.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
