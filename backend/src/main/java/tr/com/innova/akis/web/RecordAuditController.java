package tr.com.innova.akis.web;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;
import tr.com.innova.akis.metadata.ApiException;
import tr.com.innova.akis.security.AuthorizationService;
import static tr.com.innova.akis.security.PermissionCodes.*;

/** Read-only attribution from immutable successful events and legacy row audit fields. */
@RestController
@RequestMapping("/api/v1/projects/{projectUuid}/record-audit")
public class RecordAuditController {
    /** global = topology tables shared by all projects (no proje_id / arsivlenme_zamani). */
    private record Source(String table, String permission, boolean global) {}
    private static final Map<String, Source> SOURCES = Map.of(
        "connections", new Source("baglanti", TOPOLOGY_READ, true),
        "logical-schemas", new Source("mantiksal_sema", TOPOLOGY_READ, true),
        "environments", new Source("ortam", TOPOLOGY_READ, true),
        "physical-schemas", new Source("fiziksel_sema", TOPOLOGY_READ, true),
        "models", new Source("model", CATALOG_READ, false),
        "data-objects", new Source("veri_nesnesi", CATALOG_READ, false),
        "definitions", new Source("tanim", DEFINITION_READ, false),
        "folders", new Source("klasor", DEFINITION_READ, false));
    private final JdbcClient jdbc;
    private final AuthorizationService authorization;
    public RecordAuditController(JdbcClient jdbc, AuthorizationService authorization) { this.jdbc = jdbc; this.authorization = authorization; }
    public record RecordAudit(UUID uuid, String createdBy, OffsetDateTime createdAt, String updatedBy, OffsetDateTime updatedAt) {}

    @GetMapping("/{kind}")
    public List<RecordAudit> list(@PathVariable UUID projectUuid, @PathVariable String kind) {
        Source source = SOURCES.get(kind);
        if (source == null) throw new ApiException(HttpStatus.BAD_REQUEST, "AUDIT_KIND_INVALID", "Kayıt türü geçersiz.");
        authorization.requireProjectPermission(projectUuid, source.permission());
        // Paths are derived from whitelisted record kinds, never supplied SQL.
        // Data Store endpoints are nested under their owning model.
        String modelJoin = kind.equals("data-objects")
                ? " join akis.model m on m.id = r.model_id and m.proje_id = r.proje_id " : "";
        String collectionPath = kind.equals("data-objects")
                ? "'/api/v1/projects/' || p.uuid || '/models/' || m.uuid || '/data-objects'"
                : "'/api/v1/projects/' || p.uuid || '/' || :kind";
        return jdbc.sql("""
            select r.uuid, coalesce(creator.gorunen_ad, created.principal) as created_by,
                   r.olusturulma_zamani as created_at,
                   case when updated.event_id is not null then updated.principal else editor.gorunen_ad end as updated_by,
                   coalesce(updated.olay_zamani, r.guncellenme_zamani) as updated_at
              from akis.%s r join akis.proje p on %s %s
              left join akis.kullanici creator on creator.id = r.olusturan_kullanici_id
              left join akis.kullanici editor on editor.id = r.guncelleyen_kullanici_id
              left join lateral (
                select a.ayrinti->>'principal' as principal from akis.denetim_olayi a
                 where a.proje_id = p.id and a.dis_nesne_uuid = r.uuid and a.sonuc = 'BASARILI'
                   and a.eylem_kodu = 'HTTP_POST'
                   and regexp_replace(a.ayrinti->>'path', '^/api/v2/', '/api/v1/') = %s
                 order by a.olay_zamani, a.id limit 1
              ) created on true
              left join lateral (
                select a.id as event_id, a.ayrinti->>'principal' as principal, a.olay_zamani from akis.denetim_olayi a
                 where a.proje_id = p.id and a.sonuc = 'BASARILI'
                   and regexp_replace(a.ayrinti->>'path', '^/api/v2/', '/api/v1/') in (
                     (%s) || '/' || r.uuid,
                     (%s) || '/' || r.uuid || '/draft',
                     (%s) || '/' || r.uuid || '/move',
                     case when :kind = 'data-objects' then (%s) || '/' || r.uuid || '/folder' end,
                     (%s) || '/' || r.uuid || '/tests')
                   and a.eylem_kodu in ('HTTP_PUT', 'HTTP_PATCH', 'HTTP_POST')
                 order by a.olay_zamani desc, a.id desc limit 1
              ) updated on true
             where p.uuid = :project %s
            """.formatted(source.table(), source.global() ? "p.uuid = :project" : "p.id = r.proje_id", modelJoin, collectionPath,
                    collectionPath, collectionPath, collectionPath, collectionPath, collectionPath,
                    source.global() ? "" : "and r.arsivlenme_zamani is null"))
            .param("kind", kind).param("project", projectUuid)
            .query((rs, index) -> new RecordAudit(rs.getObject("uuid", UUID.class), rs.getString("created_by"), rs.getObject("created_at", OffsetDateTime.class), rs.getString("updated_by"), rs.getObject("updated_at", OffsetDateTime.class))).list();
    }
}
