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
    private record Source(String table, String permission) {}
    private static final Map<String, Source> SOURCES = Map.of(
        "connections", new Source("baglanti", TOPOLOGY_READ),
        "logical-schemas", new Source("mantiksal_sema", TOPOLOGY_READ),
        "environments", new Source("ortam", TOPOLOGY_READ),
        "physical-schemas", new Source("fiziksel_sema", TOPOLOGY_READ),
        "models", new Source("model", CATALOG_READ),
        "definitions", new Source("tanim", DEFINITION_READ),
        "folders", new Source("klasor", DEFINITION_READ));
    private final JdbcClient jdbc;
    private final AuthorizationService authorization;
    public RecordAuditController(JdbcClient jdbc, AuthorizationService authorization) { this.jdbc = jdbc; this.authorization = authorization; }
    public record RecordAudit(UUID uuid, String createdBy, OffsetDateTime createdAt, String updatedBy, OffsetDateTime updatedAt) {}

    @GetMapping("/{kind}")
    public List<RecordAudit> list(@PathVariable UUID projectUuid, @PathVariable String kind) {
        Source source = SOURCES.get(kind);
        if (source == null) throw new ApiException(HttpStatus.BAD_REQUEST, "AUDIT_KIND_INVALID", "Kayıt türü geçersiz.");
        authorization.requireProjectPermission(projectUuid, source.permission());
        return jdbc.sql("""
            select r.uuid, coalesce(creator.gorunen_ad, created.principal) as created_by,
                   r.olusturulma_zamani as created_at,
                   coalesce(updated.principal, editor.gorunen_ad) as updated_by,
                   coalesce(updated.olay_zamani, r.guncellenme_zamani) as updated_at
              from akis.%s r join akis.proje p on p.id = r.proje_id
              left join akis.kullanici creator on creator.id = r.olusturan_kullanici_id
              left join akis.kullanici editor on editor.id = r.guncelleyen_kullanici_id
              left join lateral (
                select a.ayrinti->>'principal' as principal from akis.denetim_olayi a
                 where a.proje_id = p.id and a.dis_nesne_uuid = r.uuid and a.sonuc = 'BASARILI'
                   and a.eylem_kodu = 'HTTP_POST'
                   and a.ayrinti->>'path' = '/api/v1/projects/' || p.uuid || '/' || :kind
                 order by a.olay_zamani, a.id limit 1
              ) created on true
              left join lateral (
                select a.ayrinti->>'principal' as principal, a.olay_zamani from akis.denetim_olayi a
                 where a.proje_id = p.id and a.sonuc = 'BASARILI'
                   and (a.ayrinti->>'path' = '/api/v1/projects/' || p.uuid || '/' || :kind || '/' || r.uuid
                     or a.ayrinti->>'path' = '/api/v1/projects/' || p.uuid || '/' || :kind || '/' || r.uuid || '/draft'
                     or a.ayrinti->>'path' = '/api/v1/projects/' || p.uuid || '/' || :kind || '/' || r.uuid || '/move'
                     or (:kind = 'connections' and a.ayrinti->>'path' = '/api/v1/projects/' || p.uuid || '/connections/' || r.uuid || '/versions'))
                   and a.eylem_kodu in ('HTTP_PUT', 'HTTP_PATCH', 'HTTP_POST')
                 order by a.olay_zamani desc, a.id desc limit 1
              ) updated on true
             where p.uuid = :project and r.arsivlenme_zamani is null
            """.formatted(source.table()))
            .param("kind", kind).param("project", projectUuid)
            .query((rs, index) -> new RecordAudit(rs.getObject("uuid", UUID.class), rs.getString("created_by"), rs.getObject("created_at", OffsetDateTime.class), rs.getString("updated_by"), rs.getObject("updated_at", OffsetDateTime.class))).list();
    }
}
