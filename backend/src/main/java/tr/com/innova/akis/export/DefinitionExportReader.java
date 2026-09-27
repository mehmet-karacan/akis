package tr.com.innova.akis.export;

import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Read-only, project-scoped export projection for design-time definitions. */
@Component
class DefinitionExportReader {

    private final JdbcClient jdbc;

    DefinitionExportReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record DefinitionRef(UUID uuid, String type, String code, String name, String description,
            String status, UUID folderUuid, long version, long versionCount, Integer latestVersionNumber) {
    }

    void forEach(UUID projectUuid, Consumer<DefinitionRef> consumer) {
        try (var rows = jdbc.sql("""
                        select t.uuid, t.tur, t.kod, t.ad, t.aciklama,
                               case when t.arsivlenme_zamani is null then 'AKTIF' else 'ARSIVLENDI' end as durum,
                               k.uuid as folder_uuid, t.versiyon_no,
                               coalesce(v.version_count, 0) as version_count,
                               v.latest_version_number
                          from akis.tanim t
                          left join akis.klasor k on k.id = t.klasor_id
                          left join lateral (
                               select count(*)::bigint as version_count, max(ts.surum_no)::integer as latest_version_number
                                 from akis.tanim_surumu ts
                                where ts.tanim_id = t.id
                          ) v on true
                         where t.proje_id = :projectId
                         order by t.tur, t.kod
                        """).param("projectId", projectId(projectUuid))
                .query((rs, n) -> new DefinitionRef(
                        rs.getObject("uuid", UUID.class), rs.getString("tur"), rs.getString("kod"),
                        rs.getString("ad"), rs.getString("aciklama"), rs.getString("durum"),
                        rs.getObject("folder_uuid", UUID.class), rs.getLong("versiyon_no"),
                        rs.getLong("version_count"), rs.getObject("latest_version_number", Integer.class))).stream()) {
            rows.forEach(consumer);
        }
    }

    private long projectId(UUID projectUuid) {
        Long id = jdbc.sql("select id from akis.proje where uuid = :uuid")
                .param("uuid", projectUuid).query(Long.class).single();
        if (id == null) {
            throw new IllegalArgumentException("Project not found: " + projectUuid);
        }
        return id;
    }
}
