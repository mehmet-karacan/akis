package tr.com.innova.akis.export;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.BiConsumer;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Export-local, read-only access to run history (runs, steps, events).
 *
 * <p>Deliberately does not reuse {@code ExecutionService} /
 * {@code ExecutionModels}, which intentionally stay package-private to the
 * {@code execution} package. All queries are scoped by project so a caller can
 * never read rows outside the authorized project. This mirrors the projection
 * used by {@code JdbcExecutionStore} but exposes only the fields the export
 * provider needs and keeps SQL evidence raw (the writer redacts unsafe SQL).
 *
 * <p>All collection queries are streaming-friendly: callers provide a
 * {@link Consumer} and rows are processed one at a time without building a
 * whole-dataset list in memory.
 */
@Component
class RunExportReader {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    @Autowired
    RunExportReader(DataSource dataSource, ObjectMapper objectMapper, ExportConfiguration configuration) {
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.setFetchSize(configuration.jdbcFetchSize());
        this.jdbc = JdbcClient.create(template);
        this.objectMapper = objectMapper;
    }

    RunExportReader(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    record RunRef(
            UUID runUuid,
            UUID publicationUuid,
            int attemptNumber,
            String startType,
            String status,
            OffsetDateTime createdAt,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt) {
    }

    record StepRef(
            UUID uuid,
            UUID parentUuid,
            String code,
            String type,
            int ordinal,
            String name,
            String status,
            String connectionRole,
            String risk,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt,
            Long rowCount,
            Long byteCount,
            String errorCode,
            String logCounter,
            String transactionState,
            UUID childRunUuid,
            JsonNode executedSql) {

        StepRef(UUID uuid, UUID parentUuid, String code, String type, int ordinal,
                String name, String status, String connectionRole, String risk,
                OffsetDateTime startedAt, OffsetDateTime finishedAt, Long rowCount,
                Long byteCount, String errorCode, String logCounter,
                String transactionState, UUID childRunUuid) {
            this(uuid, parentUuid, code, type, ordinal, name, status, connectionRole,
                    risk, startedAt, finishedAt, rowCount, byteCount, errorCode,
                    logCounter, transactionState, childRunUuid, null);
        }
    }

    record EventRef(
            UUID uuid,
            long eventNumber,
            String type,
            OffsetDateTime eventTime,
            JsonNode data) {
    }

    /**
     * Streams runs matching the supplied project-scoped, parametrized criteria.
     * No in-memory materialisation of the whole result set.
     */
    void forEachRun(UUID projectUuid, RunQuery query, Consumer<RunRef> consumer) {
        StringBuilder sql = new StringBuilder(runSelect());
        sql.append("""
                 where p.uuid = :projectUuid
                """);
        if (query.statuses() != null && !query.statuses().isBlank()) {
            sql.append(" and d.durum = any(string_to_array(:statuses, ','))");
        }
        if (query.environmentCode() != null && !query.environmentCode().isBlank()) {
            sql.append(" and o.kod = :environment");
        }
        if (query.definitionType() != null && !query.definitionType().isBlank()) {
            sql.append(" and t.tur = :definitionType");
        }
        if (query.scheduled() != null) {
            sql.append("""
                     and (
                         (:scheduled and j.zamanlama_id is not null)
                         or (:scheduled = false and j.zamanlama_id is null)
                     )
                    """);
        }
        if (query.fromTime() != null) {
            sql.append(" and r.olusturulma_zamani >= :fromTime");
        }
        if (query.toTime() != null) {
            sql.append(" and r.olusturulma_zamani <= :toTime");
        }
        if (query.query() != null && !query.query().isBlank()) {
            sql.append("""
                     and (
                         lower(t.ad) like '%' || lower(:query) || '%'
                         or lower(t.kod) like '%' || lower(:query) || '%'
                     )
                    """);
        }
        if (query.view() != null && !query.view().isBlank()) {
            sql.append("""
                     and (
                         (:view = 'ACTIVE' and d.durum not in ('BASARILI','BASARISIZ','IPTAL','SONUCU_BILINMIYOR'))
                         or (:view = 'RECENT' and r.olusturulma_zamani >= coalesce(:fromTime, current_timestamp - interval '24 hours') and r.olusturulma_zamani <= coalesce(:toTime, current_timestamp))
                         or (:view = 'FAILED' and d.durum in ('BASARISIZ','MUDAHALE_GEREKLI','YENIDEN_DENENEBILIR') and r.olusturulma_zamani >= coalesce(:fromTime, current_timestamp - interval '24 hours') and r.olusturulma_zamani <= coalesce(:toTime, current_timestamp))
                         or (:view = 'HISTORY' and r.olusturulma_zamani >= coalesce(:fromTime, current_timestamp - interval '7 days') and r.olusturulma_zamani <= coalesce(:toTime, current_timestamp))
                     )
                    """);
        }
        sql.append(" order by r.olusturulma_zamani desc, r.id desc");
        if (query.limit() > 0) {
            sql.append(" limit :limit");
        }

        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString())
                .param("projectUuid", projectUuid)
                .param("statuses", blankToNull(query.statuses()), Types.VARCHAR)
                .param("environment", blankToNull(query.environmentCode()), Types.VARCHAR)
                .param("definitionType", blankToNull(query.definitionType()), Types.VARCHAR)
                .param("scheduled", query.scheduled(), Types.BOOLEAN)
                .param("fromTime", query.fromTime(), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("toTime", query.toTime(), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("query", blankToNull(query.query()), Types.VARCHAR)
                .param("view", blankToNull(query.view()), Types.VARCHAR);
        if (query.limit() > 0) {
            spec = spec.param("limit", query.limit());
        }
        try (var rows = spec.query((rs, n) -> new RunRef(
                rs.getObject("run_uuid", UUID.class),
                rs.getObject("publication_uuid", UUID.class),
                rs.getInt("deneme_no"),
                rs.getString("baslatma_turu"),
                rs.getString("durum_kodu"),
                rs.getObject("olusturulma_zamani", OffsetDateTime.class),
                rs.getObject("baslama_zamani", OffsetDateTime.class),
                rs.getObject("bitis_zamani", OffsetDateTime.class))).stream()) {
            rows.forEach(consumer);
        }
    }

    /**
     * Looks up a single run by UUID within the authorized project.
     */
    Optional<RunRef> find(UUID projectUuid, UUID runUuid) {
        return jdbc.sql(runSelect() + " where p.uuid = :projectUuid and r.uuid = :runUuid")
                .param("projectUuid", projectUuid)
                .param("runUuid", runUuid)
                .query((rs, n) -> new RunRef(
                        rs.getObject("run_uuid", UUID.class),
                        rs.getObject("publication_uuid", UUID.class),
                        rs.getInt("deneme_no"),
                        rs.getString("baslatma_turu"),
                        rs.getString("durum_kodu"),
                        rs.getObject("olusturulma_zamani", OffsetDateTime.class),
                        rs.getObject("baslama_zamani", OffsetDateTime.class),
                        rs.getObject("bitis_zamani", OffsetDateTime.class)))
                .optional();
    }

    Map<UUID, RunRef> findBatch(UUID projectUuid, List<UUID> runUuids) {
        if (runUuids.isEmpty()) return Map.of();
        Map<UUID, RunRef> found = new HashMap<>();
        try (var rows = jdbc.sql(runSelect() + " where p.uuid = :projectUuid and r.uuid in (:runUuids)")
                .param("projectUuid", projectUuid)
                .param("runUuids", runUuids)
                .query((rs, n) -> new RunRef(
                        rs.getObject("run_uuid", UUID.class),
                        rs.getObject("publication_uuid", UUID.class),
                        rs.getInt("deneme_no"),
                        rs.getString("baslatma_turu"),
                        rs.getString("durum_kodu"),
                        rs.getObject("olusturulma_zamani", OffsetDateTime.class),
                        rs.getObject("baslama_zamani", OffsetDateTime.class),
                        rs.getObject("bitis_zamani", OffsetDateTime.class)))
                .stream()) {
            rows.forEach(run -> found.put(run.runUuid(), run));
        }
        return found;
    }

    /**
     * Streams steps for a run without materialising the whole list.
     */
    void forEachStep(UUID projectUuid, UUID runUuid, Consumer<StepRef> consumer) {
        forEachStepBatch(projectUuid, List.of(runUuid), (ignored, step) -> consumer.accept(step));
    }

    void forEachStepBatch(UUID projectUuid, List<UUID> runUuids, BiConsumer<UUID, StepRef> consumer) {
        if (runUuids.isEmpty()) return;
        try (var rows = jdbc.sql("""
                        select r.uuid as run_uuid, a.uuid, u.uuid as parent_uuid,
                               a.adim_kodu, a.tur as tur_kodu, a.sira_no, a.ad,
                               coalesce(d.durum, pd.durum, 'KAYDEDILMEDI') as durum_kodu,
                               k.baglanti_rolu, k.risk as risk_kodu,
                               coalesce(d.baslama_zamani, pd.baslama_zamani) as baslama_zamani,
                               coalesce(d.bitis_zamani, pd.bitis_zamani) as bitis_zamani,
                               d.satir_sayisi, d.bayt_sayisi, coalesce(d.hata_kodu, pd.hata_kodu) as hata_kodu,
                               sql.calistirilan_sql,
                               child.uuid as alt_calistirma_uuid,
                               task.content->>'logCounter' as log_counter,
                               case when k.baglanti_rolu = 'SOURCE' then 'NOT_APPLICABLE'
                                    else coalesce(d.transaction_outcome, 'NOT_ATTEMPTED') end
                                    as transaction_state
                          from akis.calistirma_adimi a
                          join akis.calistirma r on r.id = a.calistirma_id
                          join akis.proje p on p.id = r.proje_id
                          join akis.calistirma_durumu rd on rd.calistirma_id = r.id
                          join akis.is_talebi request on request.id = r.is_talebi_id
                          join akis.yayin publication on publication.id = request.yayin_id
                          join akis.senaryo scenario on scenario.id = publication.senaryo_id
                          left join lateral (select item as content from jsonb_array_elements(
                              coalesce(scenario.plan#>'{executable,definition,tasks}', '[]'::jsonb)) item
                              where item->>'id' = a.adim_kodu limit 1) task on true
                          left join akis.calistirma_adimi u on u.id = a.ust_adim_id
                          left join akis.prosedur_adim_kaniti k
                            on k.proje_id = a.proje_id and k.calistirma_adimi_id = a.id
                          left join akis.prosedur_adim_durumu d
                            on d.proje_id = a.proje_id and d.calistirma_adimi_id = a.id
                          left join akis.prosedur_sql_kaniti sql
                            on sql.proje_id = a.proje_id and sql.calistirma_adimi_id = a.id
                          left join akis.paket_adim_durumu pd
                            on pd.proje_id = a.proje_id and pd.calistirma_adimi_id = a.id
                          left join akis.calistirma child on child.id = pd.alt_calistirma_id
                         where p.uuid = :projectUuid and r.uuid in (:runUuids)
                         order by r.uuid, a.sira_no, a.id
                        """)
                .param("projectUuid", projectUuid)
                .param("runUuids", runUuids)
                .query((rs, n) -> java.util.Map.entry(rs.getObject("run_uuid", UUID.class), new StepRef(
                        rs.getObject("uuid", UUID.class),
                        rs.getObject("parent_uuid", UUID.class),
                        rs.getString("adim_kodu"),
                        rs.getString("tur_kodu"),
                        rs.getInt("sira_no"),
                        rs.getString("ad"),
                        rs.getString("durum_kodu"),
                        rs.getString("baglanti_rolu"),
                        rs.getString("risk_kodu"),
                        rs.getObject("baslama_zamani", OffsetDateTime.class),
                        rs.getObject("bitis_zamani", OffsetDateTime.class),
                        rs.getObject("satir_sayisi", Long.class),
                        rs.getObject("bayt_sayisi", Long.class),
                        rs.getString("hata_kodu"),
                        rs.getString("log_counter"),
                        rs.getString("transaction_state"),
                        rs.getObject("alt_calistirma_uuid", UUID.class),
                        readTree(rs.getString("calistirilan_sql")))))
                .stream()) {
            rows.forEach(row -> consumer.accept(row.getKey(), row.getValue()));
        }
    }

    /**
     * Streams events for a run without materialising the whole list.
     */
    void forEachEvent(UUID projectUuid, UUID runUuid, Consumer<EventRef> consumer) {
        forEachEventBatch(projectUuid, List.of(runUuid), (ignored, event) -> consumer.accept(event));
    }

    void forEachEventBatch(UUID projectUuid, List<UUID> runUuids, BiConsumer<UUID, EventRef> consumer) {
        if (runUuids.isEmpty()) return;
        try (var rows = jdbc.sql("""
                        select r.uuid as run_uuid, e.uuid, e.olay_no, e.tur as tur_kodu, e.olay_zamani, e.veri
                          from akis.calistirma_olayi e
                          join akis.calistirma r on r.id = e.calistirma_id
                          join akis.proje p on p.id = r.proje_id
                         where p.uuid = :projectUuid and r.uuid in (:runUuids)
                         order by r.uuid, e.olay_no
                        """)
                .param("projectUuid", projectUuid)
                .param("runUuids", runUuids)
                .query((rs, n) -> java.util.Map.entry(rs.getObject("run_uuid", UUID.class), new EventRef(
                        rs.getObject("uuid", UUID.class),
                        rs.getLong("olay_no"),
                        rs.getString("tur_kodu"),
                        rs.getObject("olay_zamani", OffsetDateTime.class),
                        readTree(rs.getString("veri")))))
                .stream()) {
            rows.forEach(row -> consumer.accept(row.getKey(), row.getValue()));
        }
    }

    private String runSelect() {
        return """
                select r.uuid as run_uuid, y.uuid as publication_uuid,
                       r.deneme_no, r.baslatma_turu,
                       d.durum as durum_kodu,
                       r.olusturulma_zamani, d.baslama_zamani, d.bitis_zamani
                  from akis.calistirma r
                  join akis.is_talebi j on j.id = r.is_talebi_id
                  join akis.yayin y on y.id = j.yayin_id
                  join akis.calistirma_durumu d on d.calistirma_id = r.id
                  join akis.senaryo s on s.id = y.senaryo_id
                  join akis.tanim_surumu ts on ts.id = s.tanim_surumu_id
                  join akis.tanim t on t.id = ts.tanim_id
                  join akis.ortam o on o.id = y.ortam_id
                  join akis.proje p on p.id = r.proje_id
                """;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private JsonNode readTree(String value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.readTree(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Stored run event JSON could not be read.", exception);
        }
    }

    /**
     * Parametrized, project-scoped run query. Empty or null string filters are
     * ignored; {@code scheduled} null means both manual and scheduled runs.
     */
    record RunQuery(
            String view,
            String query,
            String statuses,
            String environmentCode,
            String definitionType,
            OffsetDateTime fromTime,
            OffsetDateTime toTime,
            Boolean scheduled,
            long limit) {
    }
}
