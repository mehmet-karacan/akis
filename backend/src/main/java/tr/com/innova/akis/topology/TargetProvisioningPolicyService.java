package tr.com.innova.akis.topology;

import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tr.com.innova.akis.metadata.ApiException;

/**
 * Per-physical-schema policy for AKIS-generated target DDL (see V048). Mirrors {@code WorkAreaPolicyService}'s scope and
 * optimistic-locking shape; the policy code itself is enforced by the caller that actually generates or runs DDL.
 */
@Service
public class TargetProvisioningPolicyService {
    public enum Policy { DDL_DISABLED, DDL_GENERATE_ONLY, DDL_AUTO_CREATE }
    public record View(Policy policy, long version) { }
    private record Scope(long project, long schema) { }

    private final JdbcClient jdbc;

    public TargetProvisioningPolicyService(JdbcClient jdbc) { this.jdbc = jdbc; }

    private Scope scope(UUID project, UUID schema, boolean lock) {
        return jdbc.sql("""
                select p.id project_id,f.id schema_id from akis.proje p
                cross join akis.fiziksel_sema f join akis.baglanti b on b.id=f.baglanti_id
                where p.uuid=:project and f.uuid=:schema and p.arsivlenme_zamani is null
                  and f.durum='ETKIN' and b.durum='ETKIN' and b.saglayici_turu in ('ORACLE','POSTGRESQL')
                """ + (lock ? " for update of f" : ""))
                .param("project", project).param("schema", schema)
                .query((r, n) -> new Scope(r.getLong("project_id"), r.getLong("schema_id"))).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Fiziksel şema bulunamadı."));
    }

    private View read(Scope scope) {
        return jdbc.sql("select politika_kodu, versiyon_no from akis.hedef_saglama_politikasi where proje_id=:p and fiziksel_sema_id=:s")
                .param("p", scope.project()).param("s", scope.schema())
                .query((r, n) -> new View(Policy.valueOf(r.getString("politika_kodu")), r.getLong("versiyon_no")))
                .optional().orElse(new View(Policy.DDL_DISABLED, 0));
    }

    @Transactional(readOnly = true)
    public View get(UUID project, UUID schema) { return read(scope(project, schema, false)); }

    @Transactional
    public View save(UUID project, UUID schema, long expectedVersion, Policy policy) {
        Objects.requireNonNull(policy);
        Scope scope = scope(project, schema, true);
        if (read(scope).version() != expectedVersion)
            throw new ApiException(HttpStatus.CONFLICT, "STALE_VERSION", "Hedef sağlama politikası değişmiş; yeniden yükleyin.");
        jdbc.sql("""
                insert into akis.hedef_saglama_politikasi(proje_id,fiziksel_sema_id,politika_kodu)
                values(:p,:s,:kod)
                on conflict(proje_id,fiziksel_sema_id) do update set politika_kodu=excluded.politika_kodu,
                  versiyon_no=hedef_saglama_politikasi.versiyon_no+1, guncellenme_zamani=clock_timestamp()
                """).param("p", scope.project()).param("s", scope.schema()).param("kod", policy.name()).update();
        return read(scope);
    }
}
