package tr.com.innova.akis.execution;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
final class JdbcExecutionChunkStore implements ExecutionChunkStore {

    private final JdbcClient jdbc;

    JdbcExecutionChunkStore(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    public ChunkPage list(UUID projectUuid, UUID runUuid, UUID stepUuid,
            long afterCursor, int size, String status) {
        if (projectUuid == null || runUuid == null || stepUuid == null
                || afterCursor < 0 || size < 1 || size > 200
                || status != null && !status.matches("[A-Z_]{2,30}")) {
            throw new IllegalArgumentException("Chunk cursor or filter is invalid.");
        }
        var rows = jdbc.sql("""
                select c.id as cursor_id,c.uuid,c.sira_no,b.bolum_kodu,
                       c.alt_sinir->>'value' as alt_sinir,
                       c.ust_sinir->>'value' as ust_sinir,
                       c.son_anahtar->>'value' as son_anahtar,
                       c.payload_ozeti,c.girdi_satir_sayisi,c.girdi_bayt_sayisi,
                       c.durum,c.hedef_defter_referansi,c.olusturulma_zamani
                  from akis.yukleme_parcasi c
                  join akis.yukleme_bolumu b on b.id=c.yukleme_bolumu_id
                  join akis.veri_yukleme_plani p on p.id=b.veri_yukleme_plani_id
                  join akis.calistirma r on r.is_talebi_id=p.is_talebi_id
                  join akis.calistirma_adimi a on a.calistirma_id=r.id
                  join akis.proje pr on pr.id=r.proje_id
                 where pr.uuid=:projectUuid and r.uuid=:runUuid and a.uuid=:stepUuid
                   and a.adim_kodu=p.adim_kodu and c.id>:afterCursor
                   and (:status is null or c.durum=:status)
                 order by c.id
                 limit :limit
                """).param("projectUuid", projectUuid).param("runUuid", runUuid)
                .param("stepUuid", stepUuid).param("afterCursor", afterCursor)
                .param("status", status, java.sql.Types.VARCHAR).param("limit", size + 1)
                .query((rs, row) -> new ChunkRow(
                        rs.getLong("cursor_id"), rs.getObject("uuid", UUID.class), rs.getLong("sira_no"),
                        rs.getString("bolum_kodu"), decimal(rs.getString("alt_sinir")),
                        decimal(rs.getString("ust_sinir")), decimal(rs.getString("son_anahtar")),
                        rs.getString("payload_ozeti"), rs.getLong("girdi_satir_sayisi"),
                        rs.getLong("girdi_bayt_sayisi"), rs.getString("durum"),
                        rs.getString("hedef_defter_referansi"),
                        rs.getObject("olusturulma_zamani", java.time.OffsetDateTime.class)))
                .list();
        boolean more = rows.size() > size;
        var page = more ? rows.subList(0, size) : rows;
        Long next = more ? page.getLast().cursor() : null;
        return new ChunkPage(page, next, more);
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }
}
