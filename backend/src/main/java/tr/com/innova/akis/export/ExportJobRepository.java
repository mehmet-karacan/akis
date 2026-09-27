package tr.com.innova.akis.export;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.export.ExportModels.ExportJobRow;
import tr.com.innova.akis.export.ExportModels.ExportJobView;
import tr.com.innova.akis.export.ExportModels.ExportOutputRow;
import tr.com.innova.akis.export.ExportModels.ExportScope;
import tr.com.innova.akis.export.ExportModels.ExportStatus;

/**
 * JdbcClient repository for akis.veri_export_isi and akis.veri_export_ciktisi.
 */
@Repository
public class ExportJobRepository {

    public record ExpiredOutput(long outputId, UUID jobUuid, String fileName) {}

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public ExportJobRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ExportJobRow create(
            long projectId,
            long creatorId,
            String providerId,
            String resourceId,
            ExportScope scope,
            JsonNode filterSnapshot,
            List<String> selectedColumns,
            boolean includeDetails,
            OffsetDateTime expiryAt) {
        return create(Long.valueOf(projectId), creatorId, providerId, resourceId, scope,
                filterSnapshot, selectedColumns, includeDetails, expiryAt);
    }

    @Transactional
    public ExportJobRow create(
            Long projectId,
            long creatorId,
            String providerId,
            String resourceId,
            ExportScope scope,
            JsonNode filterSnapshot,
            List<String> selectedColumns,
            boolean includeDetails,
            OffsetDateTime expiryAt) {
        UUID uuid = UUID.randomUUID();
        return jdbc.sql("""
                        insert into akis.veri_export_isi(
                            uuid, proje_id, kullanici_id, saglayici_kodu, kaynak_kodu,
                            kapsam, filtre_snapshot, secili_kolonlar, detayli_mi,
                            durum, sona_erme_zamani, islenen_satir, sonuc_satir,
                            byte_sayisi, hata_kodu, hata_mesaji)
                        values (
                            :uuid, :projectId, :creatorId, :providerId, :resourceId,
                            :scope, cast(:filterSnapshot as jsonb), :selectedColumns, :includeDetails,
                            'QUEUED', :expiryAt, 0, 0,
                            0, null, null)
                        returning id, uuid, proje_id, kullanici_id, saglayici_kodu, kaynak_kodu,
                                  kapsam, filtre_snapshot, secili_kolonlar, detayli_mi,
                                  durum, islenen_satir, sonuc_satir, byte_sayisi,
                                  hata_kodu, hata_mesaji, sona_erme_zamani,
                                  olusturulma_zamani, baslama_zamani, bitis_zamani,
                                  guncellenme_zamani, versiyon_no
                        """)
                .param("uuid", uuid)
                .param("projectId", projectId, Types.BIGINT)
                .param("creatorId", creatorId)
                .param("providerId", providerId)
                .param("resourceId", resourceId)
                .param("scope", scope.name())
                .param("filterSnapshot", filterSnapshot.toString())
                .param("selectedColumns", selectedColumns.toArray(new String[0]), Types.ARRAY)
                .param("includeDetails", includeDetails)
                .param("expiryAt", expiryAt)
                .query(this::mapJob)
                .single();
    }

    /**
     * Claims a queued job for the worker by flipping status to RUNNING.
     * Returns empty if the job is no longer claimable.
     */
    @Transactional
    public Optional<ExportJobRow> claim(UUID uuid) {
        return jdbc.sql("""
                        update akis.veri_export_isi
                           set durum = 'RUNNING',
                               baslama_zamani = current_timestamp,
                               guncellenme_zamani = current_timestamp,
                               versiyon_no = versiyon_no + 1
                         where uuid = :uuid
                           and durum = 'QUEUED'
                        returning id, uuid, proje_id, kullanici_id, saglayici_kodu, kaynak_kodu,
                                  kapsam, filtre_snapshot, secili_kolonlar, detayli_mi,
                                  durum, islenen_satir, sonuc_satir, byte_sayisi,
                                  hata_kodu, hata_mesaji, sona_erme_zamani,
                                  olusturulma_zamani, baslama_zamani, bitis_zamani,
                                  guncellenme_zamani, versiyon_no
                        """)
                .param("uuid", uuid)
                .query(this::mapJob)
                .optional();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean updateProgress(UUID uuid, long processedRows, long resultRows, long byteSize) {
        return jdbc.sql("""
                        update akis.veri_export_isi
                           set islenen_satir = :processedRows,
                               sonuc_satir = :resultRows,
                               byte_sayisi = :byteSize,
                               guncellenme_zamani = current_timestamp,
                               versiyon_no = versiyon_no + 1
                         where uuid = :uuid
                           and durum = 'RUNNING'
                        """)
                .param("uuid", uuid)
                .param("processedRows", processedRows)
                .param("resultRows", resultRows)
                .param("byteSize", byteSize)
                .update() == 1;
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<ExportStatus> currentStatus(UUID uuid) {
        return jdbc.sql("select durum from akis.veri_export_isi where uuid = :uuid")
                .param("uuid", uuid)
                .query(String.class)
                .optional()
                .map(ExportStatus::valueOf);
    }

    @Transactional
    public boolean markTerminal(UUID uuid, ExportStatus status, String errorCode, String errorMessage) {
        return jdbc.sql("""
                        update akis.veri_export_isi
                           set durum = :status,
                               bitis_zamani = current_timestamp,
                               guncellenme_zamani = current_timestamp,
                               hata_kodu = :errorCode,
                               hata_mesaji = :errorMessage,
                               versiyon_no = versiyon_no + 1
                         where uuid = :uuid
                           and durum = 'RUNNING'
                        """)
                .param("uuid", uuid)
                .param("status", status.name())
                .param("errorCode", errorCode, Types.VARCHAR)
                .param("errorMessage", errorMessage, Types.VARCHAR)
                .update() == 1;
    }

    @Transactional
    public boolean markExpired(UUID uuid) {
        return jdbc.sql("""
                        update akis.veri_export_isi
                           set durum = 'EXPIRED',
                               bitis_zamani = current_timestamp,
                               guncellenme_zamani = current_timestamp,
                               versiyon_no = versiyon_no + 1
                         where uuid = :uuid
                           and durum in ('QUEUED','RUNNING')
                        """)
                .param("uuid", uuid)
                .update() == 1;
    }

    @Transactional
    public int expireOlderThan(OffsetDateTime cutoff) {
        return jdbc.sql("""
                        update akis.veri_export_isi
                           set durum = 'EXPIRED',
                               bitis_zamani = current_timestamp,
                               guncellenme_zamani = current_timestamp,
                               versiyon_no = versiyon_no + 1
                         where sona_erme_zamani < :cutoff
                           and durum in ('QUEUED','RUNNING','COMPLETED')
                        """)
                .param("cutoff", cutoff)
                .update();
    }

    @Transactional
    public int failStaleRunning(OffsetDateTime startedBefore) {
        return jdbc.sql("""
                        update akis.veri_export_isi
                           set durum = 'FAILED',
                               bitis_zamani = current_timestamp,
                               guncellenme_zamani = current_timestamp,
                               hata_kodu = 'EXPORT_DURATION_EXCEEDED',
                               hata_mesaji = 'Aktarım azami çalışma süresini aştı.',
                               versiyon_no = versiyon_no + 1
                         where durum = 'RUNNING'
                           and baslama_zamani < :startedBefore
                        """)
                .param("startedBefore", startedBefore)
                .update();
    }

    @Transactional(readOnly = true)
    public List<ExpiredOutput> listCleanupOutputs(OffsetDateTime terminalBefore, int limit) {
        return jdbc.sql("""
                        select c.id, i.uuid, c.dosya_yolu
                         from akis.veri_export_ciktisi c
                          join akis.veri_export_isi i on i.id = c.export_isi_id
                         where (i.durum = 'EXPIRED'
                                or (i.durum in ('FAILED', 'CANCELLED')
                                    and i.bitis_zamani < :terminalBefore))
                           and c.dosya_yolu like 'akis_%'
                           and c.dosya_yolu like '%' || cast(i.uuid as text) || '_v1.json'
                         order by c.id
                         limit :limit
                        """)
                .param("terminalBefore", terminalBefore)
                .param("limit", limit)
                .query((rs, rowNum) -> new ExpiredOutput(
                        rs.getLong("id"), rs.getObject("uuid", UUID.class), rs.getString("dosya_yolu")))
                .list();
    }

    @Transactional
    public boolean removeCleanupOutput(ExpiredOutput output, OffsetDateTime terminalBefore) {
        return jdbc.sql("""
                        delete from akis.veri_export_ciktisi c
                         using akis.veri_export_isi i
                         where i.id = c.export_isi_id
                           and (i.durum = 'EXPIRED'
                                or (i.durum in ('FAILED', 'CANCELLED')
                                    and i.bitis_zamani < :terminalBefore))
                           and i.uuid = :jobUuid
                           and c.id = :outputId
                           and c.dosya_yolu = :fileName
                        """)
                .param("jobUuid", output.jobUuid())
                .param("outputId", output.outputId())
                .param("fileName", output.fileName())
                .param("terminalBefore", terminalBefore)
                .update() == 1;
    }

    @Transactional(readOnly = true)
    public Optional<ExportJobRow> findByUuid(UUID uuid) {
        return jdbc.sql(selectJob() + " where i.uuid = :uuid")
                .param("uuid", uuid)
                .query(this::mapJob)
                .optional();
    }

    @Transactional(readOnly = true)
    public Optional<Long> findProjectId(UUID projectUuid) {
        return jdbc.sql("select id from akis.proje where uuid = :uuid")
                .param("uuid", projectUuid)
                .query(Long.class)
                .optional();
    }

    @Transactional(readOnly = true)
    public Optional<ExportJobView> findViewByUuid(UUID uuid) {
        return jdbc.sql("""
                        select uuid, saglayici_kodu, kaynak_kodu, kapsam, durum,
                               islenen_satir, sonuc_satir, byte_sayisi,
                               hata_kodu, hata_mesaji, sona_erme_zamani,
                               olusturulma_zamani, baslama_zamani, bitis_zamani,
                               guncellenme_zamani
                          from akis.veri_export_isi
                         where uuid = :uuid
                        """)
                .param("uuid", uuid)
                .query(this::mapView)
                .optional();
    }

    @Transactional(readOnly = true)
    public Optional<ExportOutputRow> findOutputForDownload(long exportJobId) {
        return jdbc.sql("""
                        select id, export_isi_id, dosya_yolu, checksum, olusturulma_zamani
                          from akis.veri_export_ciktisi
                         where export_isi_id = :exportJobId
                        """)
                .param("exportJobId", exportJobId)
                .query((rs, rowNum) -> new ExportOutputRow(
                        rs.getLong("id"),
                        rs.getLong("export_isi_id"),
                        rs.getString("dosya_yolu"),
                        rs.getString("checksum"),
                        rs.getObject("olusturulma_zamani", OffsetDateTime.class)))
                .optional();
    }

    @Transactional
    public void saveOutput(long exportJobId, String filePath, String checksum) {
        jdbc.sql("""
                        insert into akis.veri_export_ciktisi(
                            export_isi_id, dosya_yolu, checksum)
                        values (:exportJobId, :filePath, :checksum)
                        on conflict (export_isi_id) do update
                           set dosya_yolu = excluded.dosya_yolu,
                               checksum = excluded.checksum,
                               olusturulma_zamani = current_timestamp
                        """)
                .param("exportJobId", exportJobId)
                .param("filePath", filePath)
                .param("checksum", checksum)
                .update();
    }

    @Transactional
    public void removeOutputUnlessCompleted(long exportJobId) {
        jdbc.sql("""
                        delete from akis.veri_export_ciktisi c
                         using akis.veri_export_isi i
                         where i.id = c.export_isi_id
                           and i.id = :exportJobId
                           and i.durum <> 'COMPLETED'
                        """)
                .param("exportJobId", exportJobId)
                .update();
    }

    @Transactional
    public boolean cancel(UUID uuid, long creatorId) {
        return jdbc.sql("""
                        update akis.veri_export_isi
                           set durum = 'CANCELLED',
                               bitis_zamani = current_timestamp,
                               guncellenme_zamani = current_timestamp,
                               versiyon_no = versiyon_no + 1
                         where uuid = :uuid
                           and kullanici_id = :creatorId
                           and durum in ('QUEUED','RUNNING')
                        """)
                .param("uuid", uuid)
                .param("creatorId", creatorId)
                .update() == 1;
    }

    @Transactional(readOnly = true)
    public long countNonTerminalByCreator(long creatorId) {
        Long count = jdbc.sql("""
                        select count(*)
                          from akis.veri_export_isi
                         where kullanici_id = :creatorId
                           and durum in ('QUEUED','RUNNING')
                        """)
                .param("creatorId", creatorId)
                .query(Long.class)
                .single();
        return count;
    }

    @Transactional(readOnly = true)
    public List<ExportJobView> listRecentByProject(UUID projectUuid, int limit) {
        return jdbc.sql("""
                        select i.uuid, i.saglayici_kodu, i.kaynak_kodu, i.kapsam, i.durum,
                               i.islenen_satir, i.sonuc_satir, i.byte_sayisi,
                               i.hata_kodu, i.hata_mesaji, i.sona_erme_zamani,
                               i.olusturulma_zamani, i.baslama_zamani, i.bitis_zamani,
                               i.guncellenme_zamani
                          from akis.veri_export_isi i
                          join akis.proje p on p.id = i.proje_id
                         where p.uuid = :projectUuid
                         order by i.olusturulma_zamani desc
                         limit :limit
                        """)
                .param("projectUuid", projectUuid)
                .param("limit", limit)
                .query(this::mapView)
                .list();
    }

    private String selectJob() {
        return """
                select i.id, i.uuid, i.proje_id, i.kullanici_id, i.saglayici_kodu, i.kaynak_kodu,
                       i.kapsam, i.filtre_snapshot, i.secili_kolonlar, i.detayli_mi,
                       i.durum, i.islenen_satir, i.sonuc_satir, i.byte_sayisi,
                       i.hata_kodu, i.hata_mesaji, i.sona_erme_zamani,
                       i.olusturulma_zamani, i.baslama_zamani, i.bitis_zamani,
                       i.guncellenme_zamani, i.versiyon_no
                  from akis.veri_export_isi i
                """;
    }

    private ExportJobRow mapJob(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        JsonNode filterSnapshot;
        try {
            filterSnapshot = objectMapper.readTree(rs.getString("filtre_snapshot"));
        } catch (JacksonException e) {
            filterSnapshot = objectMapper.createObjectNode();
        }
        java.sql.Array columnsArray = rs.getArray("secili_kolonlar");
        List<String> selectedColumns;
        try {
            selectedColumns = columnsArray == null
                    ? List.of()
                    : List.of((String[]) columnsArray.getArray());
        } catch (java.sql.SQLException e) {
            selectedColumns = List.of();
        }
        return new ExportJobRow(
                rs.getLong("id"),
                rs.getObject("uuid", UUID.class),
                rs.getObject("proje_id", Long.class),
                rs.getLong("kullanici_id"),
                rs.getString("saglayici_kodu"),
                rs.getString("kaynak_kodu"),
                ExportScope.valueOf(rs.getString("kapsam")),
                filterSnapshot,
                selectedColumns,
                rs.getBoolean("detayli_mi"),
                ExportStatus.valueOf(rs.getString("durum")),
                rs.getLong("islenen_satir"),
                rs.getLong("sonuc_satir"),
                rs.getLong("byte_sayisi"),
                rs.getString("hata_kodu"),
                rs.getString("hata_mesaji"),
                rs.getObject("sona_erme_zamani", OffsetDateTime.class),
                rs.getObject("olusturulma_zamani", OffsetDateTime.class),
                rs.getObject("baslama_zamani", OffsetDateTime.class),
                rs.getObject("bitis_zamani", OffsetDateTime.class),
                rs.getObject("guncellenme_zamani", OffsetDateTime.class),
                rs.getLong("versiyon_no"));
    }

    private ExportJobView mapView(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ExportJobView(
                rs.getObject("uuid", UUID.class),
                rs.getString("saglayici_kodu"),
                rs.getString("kaynak_kodu"),
                ExportScope.valueOf(rs.getString("kapsam")),
                ExportStatus.valueOf(rs.getString("durum")),
                rs.getLong("islenen_satir"),
                rs.getLong("sonuc_satir"),
                rs.getLong("byte_sayisi"),
                rs.getString("hata_kodu"),
                rs.getString("hata_mesaji"),
                rs.getObject("sona_erme_zamani", OffsetDateTime.class),
                rs.getObject("olusturulma_zamani", OffsetDateTime.class),
                rs.getObject("baslama_zamani", OffsetDateTime.class),
                rs.getObject("bitis_zamani", OffsetDateTime.class));
    }
}
