package tr.com.innova.akis.export;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Safe project-scoped publication projection; release manifests are intentionally excluded. */
@Component
class PublicationExportReader {

    private final JdbcClient jdbc;

    PublicationExportReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record PublicationRef(
            UUID uuid,
            UUID scenarioUuid,
            UUID definitionUuid,
            UUID definitionVersionUuid,
            UUID environmentUuid,
            String definitionCode,
            String definitionName,
            String environmentCode,
            String environmentRisk,
            int publicationNumber,
            String status,
            String releaseHash,
            OffsetDateTime publishedAt,
            OffsetDateTime createdAt,
            long version) {
    }

    void forEach(UUID projectUuid, Consumer<PublicationRef> consumer) {
        try (var rows = jdbc.sql("""
                select y.uuid, s.uuid as scenario_uuid, t.uuid as definition_uuid,
                       v.uuid as definition_version_uuid, o.uuid as environment_uuid,
                       t.kod as definition_code, t.ad as definition_name,
                       o.kod as environment_code, o.risk as environment_risk,
                       y.yayin_no, y.durum as status,
                       y.fiziksel_manifesto ->> 'releaseHash' as release_hash,
                       y.etkinlestirilme_zamani as published_at,
                       y.olusturulma_zamani as created_at, y.versiyon_no
                  from akis.yayin y
                  join akis.proje p on p.id = y.proje_id
                  join akis.senaryo s on s.id = y.senaryo_id
                  join akis.tanim_surumu v on v.id = s.tanim_surumu_id
                  join akis.tanim t on t.id = v.tanim_id
                  join akis.ortam o on o.id = y.ortam_id
                 where p.uuid = :project and p.arsivlenme_zamani is null
                 order by y.olusturulma_zamani desc, y.id desc
                """).param("project", projectUuid)
                .query((rs, rowNum) -> new PublicationRef(
                        rs.getObject("uuid", UUID.class), rs.getObject("scenario_uuid", UUID.class),
                        rs.getObject("definition_uuid", UUID.class),
                        rs.getObject("definition_version_uuid", UUID.class),
                        rs.getObject("environment_uuid", UUID.class), rs.getString("definition_code"),
                        rs.getString("definition_name"), rs.getString("environment_code"),
                        rs.getString("environment_risk"), rs.getInt("yayin_no"),
                        rs.getString("status"), rs.getString("release_hash"),
                        rs.getObject("published_at", OffsetDateTime.class),
                        rs.getObject("created_at", OffsetDateTime.class), rs.getLong("versiyon_no"))).stream()) {
            rows.forEach(consumer);
        }
    }
}
