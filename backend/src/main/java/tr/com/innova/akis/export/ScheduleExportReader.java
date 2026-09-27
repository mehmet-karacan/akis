package tr.com.innova.akis.export;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Project-scoped, read-only schedule projection used by the export provider. */
@Component
class ScheduleExportReader {

    private final JdbcClient jdbc;

    ScheduleExportReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record ScheduleRef(
            UUID uuid,
            String code,
            String name,
            UUID publicationUuid,
            String cronExpression,
            String timeZone,
            String status,
            String conflictPolicy,
            String misfirePolicy,
            String publicationPolicy,
            OffsetDateTime nextFireTime,
            OffsetDateTime lastFireTime,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            long version) {
    }

    void forEach(UUID projectUuid, Consumer<ScheduleRef> consumer) {
        try (var rows = jdbc.sql("""
                select z.uuid, z.kod, z.ad, y.uuid as publication_uuid,
                       z.cron_ifadesi, z.zaman_dilimi, z.durum_kodu,
                       z.cakisma_politikasi, z.kacirma_politikasi, z.yayin_gorunum_kodu,
                       z.sonraki_tetikleme_zamani, z.son_tetikleme_zamani,
                       z.baslangic_zamani, z.bitis_zamani, z.versiyon_no
                  from akis.zamanlama z
                  join akis.proje p on p.id = z.proje_id
                  join akis.yayin y on y.id = z.yayin_id
                 where p.uuid = :project and p.arsivlenme_zamani is null
                   and z.arsivlenme_zamani is null
                 order by z.olusturulma_zamani desc, z.id desc
                """).param("project", projectUuid)
                .query((rs, rowNum) -> new ScheduleRef(
                        rs.getObject("uuid", UUID.class), rs.getString("kod"), rs.getString("ad"),
                        rs.getObject("publication_uuid", UUID.class), rs.getString("cron_ifadesi"),
                        rs.getString("zaman_dilimi"), rs.getString("durum_kodu"),
                        rs.getString("cakisma_politikasi"), rs.getString("kacirma_politikasi"),
                        rs.getString("yayin_gorunum_kodu"),
                        rs.getObject("sonraki_tetikleme_zamani", OffsetDateTime.class),
                        rs.getObject("son_tetikleme_zamani", OffsetDateTime.class),
                        rs.getObject("baslangic_zamani", OffsetDateTime.class),
                        rs.getObject("bitis_zamani", OffsetDateTime.class),
                        rs.getLong("versiyon_no"))).stream()) {
            rows.forEach(consumer);
        }
    }
}
