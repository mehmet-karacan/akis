package tr.com.innova.akis.execution;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import tr.com.innova.akis.execution.ExecutionModels.Actor;
import tr.com.innova.akis.execution.ExecutionModels.IdempotencyReservation;
import tr.com.innova.akis.execution.ExecutionModels.PublicationContext;
import tr.com.innova.akis.execution.ExecutionModels.RunEventRow;
import tr.com.innova.akis.execution.ExecutionModels.RunEventPage;
import tr.com.innova.akis.execution.ExecutionModels.RunRow;
import tr.com.innova.akis.execution.ExecutionModels.RunSearch;
import tr.com.innova.akis.execution.ExecutionModels.RunSummaryPage;
import tr.com.innova.akis.execution.ExecutionModels.RunSummaryRow;
import tr.com.innova.akis.execution.ExecutionModels.RunStepRow;

@Repository
public class JdbcExecutionStore implements ExecutionStore {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcExecutionStore(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean projectExists(UUID projectUuid) {
        return findProjectId(projectUuid).isPresent();
    }

    @Override
    public Optional<Long> findProjectId(UUID projectUuid) {
        return jdbc.sql("""
                        select id from akis.proje
                         where uuid = :projectUuid and arsivlenme_zamani is null
                        """)
                .param("projectUuid", projectUuid)
                .query(Long.class)
                .optional();
    }

    @Override
    public Optional<Actor> findActiveActor(long userId) {
        return jdbc.sql("""
                        select id, uuid, gorunen_ad as ad from akis.kullanici
                         where id = :id and devre_disi_birakilma_zamani is null
                        """)
                .param("id", userId)
                .query((rs, rowNum) -> new Actor(
                        rs.getLong("id"), rs.getObject("uuid", UUID.class),
                        rs.getString("ad")))
                .optional();
    }

    @Override
    public Optional<Actor> findActiveActor(String provider, String subject) {
        return jdbc.sql("""
                        select k.id, k.uuid, k.gorunen_ad as ad
                          from akis.kullanici k
                          join akis.harici_kimlik h on h.kullanici_id = k.id
                         where ((:provider = 'LOCAL_BASIC' and h.saglayici_turu='YEREL' and h.yayinlayici is null)
                                or (h.saglayici_turu='OIDC' and h.yayinlayici=:provider))
                           and h.harici_kullanici_anahtari=:subject
                           and k.devre_disi_birakilma_zamani is null
                        """)
                .param("provider", provider)
                .param("subject", subject)
                .query((rs, rowNum) -> new Actor(
                        rs.getLong("id"), rs.getObject("uuid", UUID.class),
                        rs.getString("ad")))
                .optional();
    }

    @Override
    public Optional<PublicationContext> lockPublication(
            UUID projectUuid, UUID publicationUuid) {
        return jdbc.sql("""
                        select p.id as project_id, y.id as publication_id,
                               y.uuid as publication_uuid, y.durum as durum_kodu,
                               o.risk as risk_kodu,
                               y.fiziksel_manifesto ->> 'releaseHash' as release_hash, s.plan_ozeti,
                               y.fiziksel_manifesto
                          from akis.yayin y
                          join akis.proje p on p.id = y.proje_id
                          join akis.ortam o
                            on o.id = y.ortam_id
                          join akis.senaryo s on s.id = y.senaryo_id
                         where p.uuid = :projectUuid
                           and p.arsivlenme_zamani is null
                           and y.uuid = :publicationUuid
                         for update of y
                        """)
                .param("projectUuid", projectUuid)
                .param("publicationUuid", publicationUuid)
                .query((rs, rowNum) -> new PublicationContext(
                        rs.getLong("project_id"), rs.getLong("publication_id"),
                        rs.getObject("publication_uuid", UUID.class),
                        rs.getString("durum_kodu"), rs.getString("risk_kodu"),
                        rs.getString("release_hash"), rs.getString("plan_ozeti"),
                        json(rs.getString("fiziksel_manifesto"))))
                .optional();
    }

    @Override
    public boolean reserveIdempotency(
            long projectId,
            long actorId,
            String scope,
            String keyHash,
            String requestHash,
            UUID reservationUuid) {
        return jdbc.sql("""
                        insert into akis.istek_anahtari(
                            proje_id, kullanici_id, kapsam, anahtar_ozeti,
                            istek_ozeti, durum, sona_erme_zamani, uuid,
                            olusturan_kullanici_id)
                        values (:projectId, :actorId, :scope, :keyHash,
                                :requestHash, 'ISLENIYOR',
                                current_timestamp + interval '24 hours', :uuid,
                                :actorId)
                        on conflict (proje_id, kullanici_id, kapsam, anahtar_ozeti)
                        do nothing
                        """)
                .param("projectId", projectId)
                .param("actorId", actorId)
                .param("scope", scope)
                .param("keyHash", keyHash)
                .param("requestHash", requestHash)
                .param("uuid", reservationUuid)
                .update() == 1;
    }

    @Override
    public Optional<IdempotencyReservation> lockIdempotency(
            long projectId, long actorId, String scope, String keyHash) {
        return jdbc.sql("""
                        select id, istek_ozeti, is_talebi_id
                          from akis.istek_anahtari
                         where proje_id = :projectId
                           and kullanici_id = :actorId
                           and kapsam = :scope
                           and anahtar_ozeti = :keyHash
                         for update
                        """)
                .param("projectId", projectId)
                .param("actorId", actorId)
                .param("scope", scope)
                .param("keyHash", keyHash)
                .query((rs, rowNum) -> new IdempotencyReservation(
                        rs.getLong("id"), rs.getString("istek_ozeti"),
                        rs.getObject("is_talebi_id", Long.class)))
                .optional();
    }

    @Override
    public RunRow createQueuedRun(
            PublicationContext publication,
            Actor actor,
            String requestHash,
            UUID jobRequestUuid,
            UUID runUuid,
            UUID stateUuid,
            UUID eventUuid) {
        return createQueuedRun(publication, actor, requestHash, jobRequestUuid, runUuid, stateUuid, eventUuid, null);
    }

    @Override
    public RunRow createQueuedRun(
            PublicationContext publication,
            Actor actor,
            String requestHash,
            UUID jobRequestUuid,
            UUID runUuid,
            UUID stateUuid,
            UUID eventUuid,
            Integer batchRows) {
        return createQueuedRun(publication, actor, requestHash, jobRequestUuid, runUuid, stateUuid, eventUuid, batchRows, null, null);
    }

    @Override
    public RunRow createQueuedRun(
            PublicationContext publication,
            Actor actor,
            String requestHash,
            UUID jobRequestUuid,
            UUID runUuid,
            UUID stateUuid,
            UUID eventUuid,
            Integer batchRows,
            Long scheduleId,
            java.time.OffsetDateTime plannedAt) {
        ObjectNode parameters = objectMapper.createObjectNode();
        if (batchRows != null) parameters.put("batchRows", batchRows);
        long jobRequestId = jdbc.sql("""
                        insert into akis.is_talebi(
                            proje_id, yayin_id, istek_ozeti, is_turu, oncelik,
                            parametre_sema_surumu, parametre, uuid, olusturan_kullanici_id,
                            zamanlama_id, planlanan_zaman)
                        values (:projectId, :publicationId, :requestHash, 'CALISTIR', 50,
                                1, cast(:parameters as jsonb), :uuid, :actorId,
                                :scheduleId, :plannedAt)
                        returning id
                        """)
                .param("parameters", parameters.toString())
                .param("projectId", publication.projectId())
                .param("publicationId", publication.publicationId())
                .param("requestHash", requestHash)
                .param("uuid", jobRequestUuid)
                .param("actorId", actor.id())
                .param("scheduleId", scheduleId)
                .param("plannedAt", plannedAt)
                .query(Long.class)
                .single();
        long runId = jdbc.sql("""
                        insert into akis.calistirma(
                            proje_id, is_talebi_id, deneme_no, yayin_ozeti, plan_ozeti,
                            baslatma_turu, uuid, olusturan_kullanici_id)
                        values (:projectId, :jobRequestId, 1, :releaseHash, :planHash,
                                'ILK', :uuid, :actorId)
                        returning id
                        """)
                .param("projectId", publication.projectId())
                .param("jobRequestId", jobRequestId)
                .param("releaseHash", publication.releaseHash())
                .param("planHash", publication.planHash())
                .param("uuid", runUuid)
                .param("actorId", actor.id())
                .query(Long.class)
                .single();
        jdbc.sql("""
                        insert into akis.calistirma_durumu(
                            proje_id, calistirma_id, durum, son_olay_no,
                            uuid, olusturan_kullanici_id, guncellenme_zamani,
                            guncelleyen_kullanici_id)
                        values (:projectId, :runId, 'BEKLIYOR', 1,
                                :uuid, :actorId, current_timestamp, :actorId)
                        """)
                .param("projectId", publication.projectId())
                .param("runId", runId)
                .param("uuid", stateUuid)
                .param("actorId", actor.id())
                .update();
        ObjectNode eventData = objectMapper.createObjectNode();
        eventData.put("planHash", publication.planHash());
        eventData.put("releaseHash", publication.releaseHash());
        eventData.put("publicationUuid", publication.publicationUuid().toString());
        if (batchRows != null) eventData.put("batchRows", batchRows);
        insertEvent(
                publication.projectId(), runId, 1, "RUN_REQUESTED",
                eventData, eventUuid, actor.id());
        return findByJobRequestId(publication.projectId(), jobRequestId).orElseThrow();
    }

    @Override
    public void completeIdempotency(long reservationId, long jobRequestId, RunRow run) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jobRequestUuid", run.jobRequestUuid().toString());
        response.put("runUuid", run.runUuid().toString());
        jdbc.sql("""
                        update akis.istek_anahtari
                           set is_talebi_id = :jobRequestId,
                               durum = 'TAMAMLANDI',
                               yanit_sema_surumu = 1,
                               yanit = cast(:response as jsonb),
                               guncellenme_zamani = current_timestamp,
                               guncelleyen_kullanici_id = kullanici_id,
                               versiyon_no = versiyon_no + 1
                         where id = :reservationId
                        """)
                .param("jobRequestId", jobRequestId)
                .param("response", response.toString())
                .param("reservationId", reservationId)
                .update();
    }

    @Override
    public Optional<RunRow> findByJobRequestId(long projectId, long jobRequestId) {
        return jdbc.sql(runSelect() + """
                         where j.proje_id = :projectId and j.id = :jobRequestId
                        """)
                .param("projectId", projectId)
                .param("jobRequestId", jobRequestId)
                .query(this::mapRun)
                .optional();
    }

    @Override
    public Optional<RunRow> find(UUID projectUuid, UUID runUuid) {
        return jdbc.sql(runSelect() + """
                         where p.uuid = :projectUuid and r.uuid = :runUuid
                        """)
                .param("projectUuid", projectUuid)
                .param("runUuid", runUuid)
                .query(this::mapRun)
                .optional();
    }

    @Override
    public Optional<RunRow> lock(UUID projectUuid, UUID runUuid) {
        return jdbc.sql(runSelect() + """
                         where p.uuid = :projectUuid and r.uuid = :runUuid
                         for update of d
                        """)
                .param("projectUuid", projectUuid)
                .param("runUuid", runUuid)
                .query(this::mapRun)
                .optional();
    }

    @Override
    public List<RunRow> list(UUID projectUuid) {
        return jdbc.sql(runSelect() + """
                         where p.uuid = :projectUuid
                         order by r.olusturulma_zamani desc, r.id desc
                        """)
                .param("projectUuid", projectUuid)
                .query(this::mapRun)
                .list();
    }

    @Override
    public RunSummaryPage search(UUID projectUuid, RunSearch search) {
        String joinsAndFilter = """
                  from akis.calistirma r
                  join akis.is_talebi j on j.id = r.is_talebi_id
                  join akis.yayin y on y.id = j.yayin_id
                  join akis.senaryo s on s.id = y.senaryo_id
                  join akis.tanim_surumu ts on ts.id = s.tanim_surumu_id
                  join akis.tanim t on t.id = ts.tanim_id
                  join akis.ortam o on o.id = y.ortam_id
                  join akis.calistirma_durumu d on d.calistirma_id = r.id
                  join akis.proje p on p.id = r.proje_id
                  left join akis.kullanici k on k.id = j.olusturan_kullanici_id
                 where p.uuid = :projectUuid
                   and (:query is null or lower(t.ad) like '%' || lower(:query) || '%'
                        or lower(t.kod) like '%' || lower(:query) || '%')
                   and (:statuses is null or d.durum = any(string_to_array(:statuses, ',')))
                   and (:environment is null or o.kod = :environment)
                   and (:definitionType is null or t.tur = :definitionType)
                   and (
                        (:view = 'ACTIVE' and d.durum not in ('BASARILI','BASARISIZ','IPTAL','SONUCU_BILINMIYOR'))
                        or (:view = 'RECENT' and r.olusturulma_zamani >= coalesce(:fromTime, current_timestamp - interval '24 hours') and r.olusturulma_zamani <= coalesce(:toTime, current_timestamp))
                        or (:view = 'FAILED' and d.durum in ('BASARISIZ','MUDAHALE_GEREKLI','YENIDEN_DENENEBILIR') and r.olusturulma_zamani >= coalesce(:fromTime, current_timestamp - interval '24 hours') and r.olusturulma_zamani <= coalesce(:toTime, current_timestamp))
                        or (:view = 'HISTORY' and r.olusturulma_zamani >= coalesce(:fromTime, current_timestamp - interval '7 days') and r.olusturulma_zamani <= coalesce(:toTime, current_timestamp))
                   )
                """;
        long total = bindSearch(jdbc.sql("select count(*) " + joinsAndFilter), projectUuid, search)
                .query(Long.class).single();
        List<RunSummaryRow> items = bindSearch(jdbc.sql("""
                        select j.id as job_request_id, r.id as run_id, d.id as state_id,
                               j.uuid as job_request_uuid, r.uuid as run_uuid,
                               y.uuid as publication_uuid, r.deneme_no, r.baslatma_turu,
                               d.durum as durum_kodu, r.yayin_ozeti, r.plan_ozeti, d.son_olay_no,
                               r.olusturulma_zamani, d.baslama_zamani, d.bitis_zamani,
                               d.iptal_isteme_zamani,
                               t.uuid as definition_uuid, t.kod as definition_code,
                               t.ad as definition_name, t.tur as definition_type,
                               o.uuid as environment_uuid, o.kod as environment_code,
                               o.ad as environment_name, o.risk as environment_risk,
                               coalesce(k.gorunen_ad, 'Kaydedilmemiş') as initiator_name,
                               (select sum(ad.satir_sayisi)::bigint
                                  from akis.calistirma_adimi ca
                                  join akis.prosedur_adim_kaniti ak on ak.calistirma_adimi_id = ca.id
                                  join akis.prosedur_adim_durumu ad on ad.calistirma_adimi_id = ca.id
                                 where ca.calistirma_id = r.id
                                   and ak.baglanti_rolu = 'SOURCE' and ad.durum = 'BASARILI') as selected_rows,
                               (select sum(ad.satir_sayisi)::bigint
                                  from akis.calistirma_adimi ca
                                  join akis.prosedur_adim_durumu ad on ad.calistirma_adimi_id = ca.id
                                 where ca.calistirma_id = r.id
                                   and ad.durum = 'BASARILI' and d.durum = 'BASARILI'
                                   and exists (select 1 from jsonb_array_elements(
                                       coalesce(s.plan#>'{executable,definition,tasks}', '[]'::jsonb)) task
                                       where task->>'id' = ca.adim_kodu and task->>'logCounter' = 'INSERT'
                                         and (coalesce(task->>'transactionMode', 'AUTOCOMMIT') <> 'TRANSACTION'
                                              or not exists (select 1 from akis.prosedur_adim_durumu failure
                                                  where failure.calistirma_id = r.id and failure.durum <> 'BASARILI')))) as inserted_rows
                        """ + joinsAndFilter + """
                         order by r.olusturulma_zamani desc, r.id desc
                         limit :size offset :offset
                        """), projectUuid, search)
                .param("size", search.size())
                .param("offset", search.page() * search.size())
                .query((rs, rowNum) -> new RunSummaryRow(
                        mapRun(rs, rowNum), rs.getObject("definition_uuid", UUID.class),
                        rs.getString("definition_code"), rs.getString("definition_name"),
                        rs.getString("definition_type"),
                        rs.getObject("environment_uuid", UUID.class),
                        rs.getString("environment_code"), rs.getString("environment_name"),
                        rs.getString("environment_risk"), rs.getString("initiator_name"),
                        rs.getObject("selected_rows", Long.class),
                        rs.getObject("inserted_rows", Long.class)))
                .list();
        return new RunSummaryPage(items, total, search.page(), search.size());
    }

    private JdbcClient.StatementSpec bindSearch(
            JdbcClient.StatementSpec statement, UUID projectUuid, RunSearch search) {
        return statement
                .param("projectUuid", projectUuid)
                .param("query", blankToNull(search.query()), Types.VARCHAR)
                .param("statuses", blankToNull(search.statuses()), Types.VARCHAR)
                .param("environment", blankToNull(search.environmentCode()), Types.VARCHAR)
                .param("definitionType", blankToNull(search.definitionType()), Types.VARCHAR)
                .param("view", search.view())
                .param("fromTime", search.from(), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("toTime", search.to(), Types.TIMESTAMP_WITH_TIMEZONE);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @Override
    public List<RunEventRow> listEvents(UUID projectUuid, UUID runUuid) {
        return jdbc.sql("""
                        select e.uuid, e.olay_no, e.tur as tur_kodu, e.olay_zamani, e.veri
                          from akis.calistirma_olayi e
                          join akis.calistirma r on r.id = e.calistirma_id
                          join akis.proje p on p.id = r.proje_id
                         where p.uuid = :projectUuid and r.uuid = :runUuid
                         order by e.olay_no
                        """)
                .param("projectUuid", projectUuid)
                .param("runUuid", runUuid)
                .query((rs, rowNum) -> new RunEventRow(
                        rs.getObject("uuid", UUID.class), rs.getLong("olay_no"),
                        rs.getString("tur_kodu"),
                        rs.getObject("olay_zamani", OffsetDateTime.class),
                        json(rs.getString("veri"))))
                .list();
    }

    @Override
    public RunEventPage listEvents(UUID projectUuid, UUID runUuid, long after, int size) {
        List<RunEventRow> rows = jdbc.sql("""
                        select e.uuid, e.olay_no, e.tur as tur_kodu, e.olay_zamani, e.veri
                          from akis.calistirma_olayi e
                          join akis.calistirma r on r.id = e.calistirma_id
                          join akis.proje p on p.id = r.proje_id
                         where p.uuid = :projectUuid and r.uuid = :runUuid
                           and e.olay_no > :after
                         order by e.olay_no
                         limit :limit
                        """)
                .param("projectUuid", projectUuid).param("runUuid", runUuid)
                .param("after", after).param("limit", size + 1)
                .query((rs, rowNum) -> new RunEventRow(
                        rs.getObject("uuid", UUID.class), rs.getLong("olay_no"),
                        rs.getString("tur_kodu"),
                        rs.getObject("olay_zamani", OffsetDateTime.class),
                        json(rs.getString("veri"))))
                .list();
        boolean hasMore = rows.size() > size;
        List<RunEventRow> items = hasMore ? rows.subList(0, size) : rows;
        Long nextCursor = items.isEmpty() ? null : items.getLast().eventNumber();
        return new RunEventPage(List.copyOf(items), nextCursor, hasMore);
    }

    @Override
    public List<RunStepRow> listSteps(UUID projectUuid, UUID runUuid) {
        return jdbc.sql("""
                        select a.uuid, u.uuid as parent_uuid, a.adim_kodu, a.tur as tur_kodu, a.sira_no, a.ad,
                               coalesce(d.durum, pd.durum, 'KAYDEDILMEDI') as durum_kodu,
                               k.baglanti_rolu, k.risk as risk_kodu,
                               coalesce(d.baslama_zamani, pd.baslama_zamani) as baslama_zamani,
                               coalesce(d.bitis_zamani, pd.bitis_zamani) as bitis_zamani,
                               d.satir_sayisi, d.bayt_sayisi, coalesce(d.hata_kodu, pd.hata_kodu) as hata_kodu,
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
                          left join akis.paket_adim_durumu pd
                            on pd.proje_id = a.proje_id and pd.calistirma_adimi_id = a.id
                          left join akis.calistirma child on child.id = pd.alt_calistirma_id
                         where p.uuid = :projectUuid and r.uuid = :runUuid
                         order by a.sira_no, a.id
                        """)
                .param("projectUuid", projectUuid)
                .param("runUuid", runUuid)
                .query((rs, rowNum) -> new RunStepRow(
                        rs.getObject("uuid", UUID.class), rs.getObject("parent_uuid", UUID.class),
                        rs.getString("adim_kodu"),
                        rs.getString("tur_kodu"), rs.getInt("sira_no"), rs.getString("ad"),
                        rs.getString("durum_kodu"), rs.getString("baglanti_rolu"),
                        rs.getString("risk_kodu"),
                        rs.getObject("baslama_zamani", OffsetDateTime.class),
                        rs.getObject("bitis_zamani", OffsetDateTime.class),
                        rs.getObject("satir_sayisi", Long.class),
                        rs.getObject("bayt_sayisi", Long.class), rs.getString("hata_kodu"),
                        rs.getString("log_counter"), rs.getString("transaction_state"),
                        rs.getObject("alt_calistirma_uuid", UUID.class)))
                .list();
    }

    @Override
    public boolean hasCompleteInputSnapshot(UUID projectUuid, UUID runUuid) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        select exists(
                            select 1
                              from akis.calistirma r
                              join akis.calistirma_girdi_goruntusu g
                                on g.is_talebi_id = r.is_talebi_id
                              join akis.proje p on p.id = r.proje_id
                             where p.uuid = :projectUuid and r.uuid = :runUuid
                               and g.durum = 'COMPLETE')
                            or exists(
                            select 1
                              from akis.calistirma r
                              join akis.proje p on p.id = r.proje_id
                              join akis.is_talebi it on it.proje_id = r.proje_id and it.id = r.is_talebi_id
                              join akis.yayin y on y.proje_id = it.proje_id and y.id = it.yayin_id
                              join akis.senaryo s on s.id = y.senaryo_id
                             where p.uuid = :projectUuid and r.uuid = :runUuid
                               and r.yayin_ozeti = (y.fiziksel_manifesto ->> 'releaseHash')
                               and r.plan_ozeti = s.plan_ozeti)
                        """)
                .param("projectUuid", projectUuid)
                .param("runUuid", runUuid)
                .query(Boolean.class)
                .single());
    }

    @Override
    public RunRow cancelQueued(RunRow run, Actor actor, UUID eventUuid) {
        long nextEvent = run.lastEventNumber() + 1;
        int updated = jdbc.sql("""
                        update akis.calistirma_durumu
                           set durum = 'IPTAL',
                               son_olay_no = :eventNumber,
                               bitis_zamani = current_timestamp,
                               guncellenme_zamani = current_timestamp,
                               guncelleyen_kullanici_id = :actorId,
                               versiyon_no = versiyon_no + 1
                         where id = :stateId and durum = 'BEKLIYOR'
                        """)
                .param("eventNumber", nextEvent)
                .param("actorId", actor.id())
                .param("stateId", run.stateId())
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Queued run state changed while locked.");
        }
        insertEvent(
                projectId(run.runId()), run.runId(), nextEvent, "RUN_CANCELLED",
                objectMapper.createObjectNode(), eventUuid, actor.id());
        return findInternal(run.runId()).orElseThrow();
    }

    private long projectId(long runId) {
        return jdbc.sql("select proje_id from akis.calistirma where id = :runId")
                .param("runId", runId)
                .query(Long.class)
                .single();
    }

    private Optional<RunRow> findInternal(long runId) {
        return jdbc.sql(runSelect() + " where r.id = :runId")
                .param("runId", runId)
                .query(this::mapRun)
                .optional();
    }

    @Override
    public boolean releaseQuarantinedTarget(RunRow run, Actor actor, String reason) {
        Integer released = jdbc.sql("""
                        update akis.hedef_kaynagi hk
                           set durum = 'BOS', calistirma_id = null, kiralama_bitis_zamani = null,
                               nesil_no = hk.nesil_no + 1, guncellenme_zamani = clock_timestamp(),
                               guncelleyen_kullanici_id = :actorId, versiyon_no = hk.versiyon_no + 1
                          from akis.calistirma_durumu cd
                         where cd.calistirma_id = :runId and hk.id = cd.hedef_kaynagi_id and hk.durum = 'ASKIDA'
                        """).param("runId", run.runId()).param("actorId", actor.id()).update();
        if (released == null || released == 0) return false;
        Long eventNumber = jdbc.sql("""
                        update akis.calistirma_durumu
                           set durum = case when durum = 'MUDAHALE_GEREKLI' then 'BASARISIZ' else durum end,
                               kiralama_bitis_zamani = null, bitis_zamani = coalesce(bitis_zamani, clock_timestamp()),
                               son_olay_no = son_olay_no + 1, guncellenme_zamani = clock_timestamp(),
                               guncelleyen_kullanici_id = :actorId, versiyon_no = versiyon_no + 1
                         where calistirma_id = :runId
                        returning son_olay_no
                        """).param("runId", run.runId()).param("actorId", actor.id()).query(Long.class).single();
        ObjectNode data = objectMapper.createObjectNode();
        data.put("reason", reason);
        data.put("actor", actor.name());
        long projectId = jdbc.sql("select proje_id from akis.calistirma where id=:id").param("id", run.runId()).query(Long.class).single();
        insertEvent(projectId, run.runId(), eventNumber, "TARGET_RELEASED", data, UUID.randomUUID(), actor.id());
        return true;
    }

    private void insertEvent(
            long projectId,
            long runId,
            long eventNumber,
            String type,
            JsonNode data,
            UUID eventUuid,
            long actorId) {
        jdbc.sql("""
                        insert into akis.calistirma_olayi(
                            proje_id, calistirma_id, olay_no, tur,
                            olay_zamani, veri_sema_surumu, veri, uuid,
                            olusturan_kullanici_id)
                        values (:projectId, :runId, :eventNumber, :type,
                                current_timestamp, 1, cast(:data as jsonb), :uuid,
                                :actorId)
                        """)
                .param("projectId", projectId)
                .param("runId", runId)
                .param("eventNumber", eventNumber)
                .param("type", type)
                .param("data", data.toString())
                .param("uuid", eventUuid)
                .param("actorId", actorId)
                .update();
    }

    private String runSelect() {
        return """
                select j.id as job_request_id, r.id as run_id, d.id as state_id,
                       j.uuid as job_request_uuid, r.uuid as run_uuid,
                       y.uuid as publication_uuid, r.deneme_no, r.baslatma_turu,
                       d.durum as durum_kodu, r.yayin_ozeti, r.plan_ozeti, d.son_olay_no,
                       r.olusturulma_zamani, d.baslama_zamani, d.bitis_zamani,
                       d.iptal_isteme_zamani
                  from akis.calistirma r
                  join akis.is_talebi j on j.id = r.is_talebi_id
                  join akis.yayin y on y.id = j.yayin_id
                  join akis.calistirma_durumu d on d.calistirma_id = r.id
                  join akis.proje p on p.id = r.proje_id
                """;
    }

    private RunRow mapRun(ResultSet rs, int rowNum) throws SQLException {
        return new RunRow(
                rs.getLong("job_request_id"), rs.getLong("run_id"), rs.getLong("state_id"),
                rs.getObject("job_request_uuid", UUID.class),
                rs.getObject("run_uuid", UUID.class),
                rs.getObject("publication_uuid", UUID.class),
                rs.getInt("deneme_no"), rs.getString("baslatma_turu"),
                rs.getString("durum_kodu"), rs.getString("yayin_ozeti"),
                rs.getString("plan_ozeti"),
                rs.getLong("son_olay_no"),
                rs.getObject("olusturulma_zamani", OffsetDateTime.class),
                rs.getObject("baslama_zamani", OffsetDateTime.class),
                rs.getObject("bitis_zamani", OffsetDateTime.class),
                rs.getObject("iptal_isteme_zamani", OffsetDateTime.class));
    }

    private JsonNode json(String value) {
        try {
            return objectMapper.readTree(value);
        }
        catch (JacksonException exception) {
            throw new IllegalStateException("Stored run event JSON could not be read.", exception);
        }
    }
}
