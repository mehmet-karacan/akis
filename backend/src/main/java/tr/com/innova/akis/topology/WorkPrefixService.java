package tr.com.innova.akis.topology;

import java.util.UUID;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tr.com.innova.akis.metadata.ApiException;
import tr.com.innova.akis.knowledge.WorkObjectPrefixes;

@Service
public class WorkPrefixService {
    private final JdbcClient jdbc;
    public WorkPrefixService(JdbcClient jdbc) { this.jdbc = jdbc; }
    public record View(WorkObjectPrefixes prefixes, long version, String origin) { }
    private record Scope(long projectId, long connectionId, Long schemaId) { }

    private Scope scope(UUID project, UUID connection, UUID schema, boolean lock) {
        String query = schema == null ? """
            select p.id project_id, b.id connection_id, null::bigint schema_id
            from akis.proje p join akis.baglanti b on b.proje_id=p.id
            where p.uuid=:project and b.uuid=:subject and p.arsivlenme_zamani is null
              and b.arsivlenme_zamani is null and b.saglayici_turu='ORACLE'
            """ : """
            select p.id project_id, b.id connection_id, f.id schema_id
            from akis.proje p join akis.baglanti b on b.proje_id=p.id
            join akis.fiziksel_sema f on f.proje_id=p.id and f.baglanti_id=b.id
            where p.uuid=:project and f.uuid=:subject and p.arsivlenme_zamani is null
              and b.arsivlenme_zamani is null and f.arsivlenme_zamani is null and b.saglayici_turu='ORACLE'
            """;
        return jdbc.sql(query + (lock ? " for update of b" : ""))
                .param("project", project).param("subject", schema == null ? connection : schema)
                .query((rs, n) -> new Scope(rs.getLong("project_id"), rs.getLong("connection_id"), rs.getObject("schema_id", Long.class)))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Oracle bağlantısı/şeması bulunamadı."));
    }
    private Optional<View> stored(Scope scope) {
        return jdbc.sql("select loading_prefix,integration_prefix,error_prefix,versiyon_no from akis.calisma_nesnesi_prefix where proje_id=:p and baglanti_id=:b and fiziksel_sema_id is not distinct from :s")
                .param("p", scope.projectId()).param("b", scope.connectionId()).param("s", scope.schemaId(), java.sql.Types.BIGINT)
                .query((rs,n) -> new View(new WorkObjectPrefixes(rs.getString(1),rs.getString(2),rs.getString(3)),rs.getLong(4),scope.schemaId() == null ? "CONNECTION" : "SCHEMA"))
                .optional();
    }
    @Transactional(readOnly = true)
    public View get(UUID project, UUID connection, UUID schema) {
        Scope scope = scope(project, connection, schema, false);
        return effective(scope);
    }
    private View effective(Scope scope) {
        return stored(scope).orElseGet(() -> {
            if (scope.schemaId() != null) {
                View inherited = stored(new Scope(scope.projectId(), scope.connectionId(), null)).orElse(new View(WorkObjectPrefixes.DEFAULTS, 0, "PLATFORM"));
                return new View(inherited.prefixes(), 0, inherited.origin());
            }
            return new View(WorkObjectPrefixes.DEFAULTS, 0, "PLATFORM");
        });
    }
    @Transactional
    public View save(UUID project, UUID connection, UUID schema, long expectedVersion, WorkObjectPrefixes prefixes) {
        if (prefixes == null) throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED", "Prefix alanları zorunludur.");
        Scope scope = scope(project, connection, schema, true);
        long existing = stored(scope).map(View::version).orElse(0L);
        if (expectedVersion != existing) throw new ApiException(HttpStatus.CONFLICT, "STALE_VERSION", "Prefix ayarları değişmiş; yeniden yükleyin.");
        if (existing == 0) {
            jdbc.sql("insert into akis.calisma_nesnesi_prefix(proje_id,baglanti_id,fiziksel_sema_id,loading_prefix,integration_prefix,error_prefix) values(:p,:b,:s,:l,:i,:e)")
                .param("p", scope.projectId()).param("b", scope.connectionId()).param("s", scope.schemaId(), java.sql.Types.BIGINT)
                .param("l", prefixes.loading()).param("i", prefixes.integration()).param("e", prefixes.error()).update();
        } else {
            jdbc.sql("update akis.calisma_nesnesi_prefix set loading_prefix=:l,integration_prefix=:i,error_prefix=:e,versiyon_no=versiyon_no+1,guncellenme_zamani=current_timestamp where proje_id=:p and baglanti_id=:b and fiziksel_sema_id is not distinct from :s")
                .param("p", scope.projectId()).param("b", scope.connectionId()).param("s", scope.schemaId(), java.sql.Types.BIGINT)
                .param("l", prefixes.loading()).param("i", prefixes.integration()).param("e", prefixes.error()).update();
        }
        return stored(scope).orElseThrow();
    }
    @Transactional
    public View inherit(UUID project, UUID schema, long expectedVersion) {
        Scope scope = scope(project, null, schema, true);
        long existing = stored(scope).map(View::version).orElse(0L);
        if (expectedVersion != existing) throw new ApiException(HttpStatus.CONFLICT, "STALE_VERSION", "Prefix ayarları değişmiş.");
        jdbc.sql("delete from akis.calisma_nesnesi_prefix where proje_id=:p and fiziksel_sema_id=:s")
                .param("p",scope.projectId()).param("s",scope.schemaId()).update();
        return effective(scope);
    }
}
