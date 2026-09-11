package tr.com.innova.akis.execution;

import java.sql.ResultSet;
import java.sql.SQLException;
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
import tr.com.innova.akis.execution.ExecutionModels.RunRow;
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
                        select id from entegrasyon.proje
                         where uuid = :projectUuid and durum_kodu = 'AKTIF'
                        """)
                .param("projectUuid", projectUuid)
                .query(Long.class)
                .optional();
    }

    @Override
    public Optional<Actor> findActiveActor(String provider, String subject) {
        return jdbc.sql("""
                        select id, uuid, ad
                          from entegrasyon.kullanici
                         where oidc_saglayici = :provider
                           and oidc_ozne = :subject
                           and durum_kodu = 'AKTIF'
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
                               y.uuid as publication_uuid, y.durum_kodu,
                               o.risk_kodu, y.release_hash, s.plan_ozeti,
                               y.fiziksel_manifesto
                          from entegrasyon.yayin y
                          join entegrasyon.proje p on p.id = y.proje_id
                          join entegrasyon.ortam o
                            on o.proje_id = y.proje_id and o.id = y.ortam_id
                          join entegrasyon.senaryo s on s.id = y.senaryo_id
                         where p.uuid = :projectUuid
                           and p.durum_kodu = 'AKTIF'
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
                        insert into entegrasyon.istek_anahtari(
                            proje_id, kullanici_id, kapsam_kodu, anahtar_ozeti,
                            istek_ozeti, durum_kodu, sona_erme_zamani, uuid,
                            olusturan_kullanici_id)
                        values (:projectId, :actorId, :scope, :keyHash,
                                :requestHash, 'ISLENIYOR',
                                current_timestamp + interval '24 hours', :uuid,
                                :actorId)
                        on conflict (proje_id, kullanici_id, kapsam_kodu, anahtar_ozeti)
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
                          from entegrasyon.istek_anahtari
                         where proje_id = :projectId
                           and kullanici_id = :actorId
                           and kapsam_kodu = :scope
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
        long jobRequestId = jdbc.sql("""
                        insert into entegrasyon.is_talebi(
                            proje_id, yayin_id, istek_ozeti, is_turu, oncelik,
                            parametre_surumu, parametre, uuid, olusturan_kullanici_id)
                        values (:projectId, :publicationId, :requestHash, 'RUN', 50,
                                1, '{}'::jsonb, :uuid, :actorId)
                        returning id
                        """)
                .param("projectId", publication.projectId())
                .param("publicationId", publication.publicationId())
                .param("requestHash", requestHash)
                .param("uuid", jobRequestUuid)
                .param("actorId", actor.id())
                .query(Long.class)
                .single();
        long runId = jdbc.sql("""
                        insert into entegrasyon.calistirma(
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
                        insert into entegrasyon.calistirma_durumu(
                            proje_id, calistirma_id, durum_kodu, son_olay_no,
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
                        update entegrasyon.istek_anahtari
                           set is_talebi_id = :jobRequestId,
                               durum_kodu = 'TAMAMLANDI',
                               yanit_surumu = 1,
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
    public List<RunEventRow> listEvents(UUID projectUuid, UUID runUuid) {
        return jdbc.sql("""
                        select e.uuid, e.olay_no, e.tur_kodu, e.olay_zamani, e.veri
                          from entegrasyon.calistirma_olayi e
                          join entegrasyon.calistirma r on r.id = e.calistirma_id
                          join entegrasyon.proje p on p.id = r.proje_id
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
    public List<RunStepRow> listSteps(UUID projectUuid, UUID runUuid) {
        return jdbc.sql("""
                        select a.uuid, a.adim_kodu, a.tur_kodu, a.sira_no, a.ad,
                               coalesce(d.durum_kodu, 'KAYDEDILMEDI') as durum_kodu,
                               k.baglanti_rolu, k.risk_kodu,
                               d.baslama_zamani, d.bitis_zamani,
                               d.satir_sayisi, d.bayt_sayisi, d.hata_kodu
                          from entegrasyon.calistirma_adimi a
                          join entegrasyon.calistirma r on r.id = a.calistirma_id
                          join entegrasyon.proje p on p.id = r.proje_id
                          left join entegrasyon.prosedur_adim_kaniti k
                            on k.proje_id = a.proje_id and k.calistirma_adimi_id = a.id
                          left join entegrasyon.prosedur_adim_durumu d
                            on d.proje_id = a.proje_id and d.calistirma_adimi_id = a.id
                         where p.uuid = :projectUuid and r.uuid = :runUuid
                         order by a.sira_no, a.id
                        """)
                .param("projectUuid", projectUuid)
                .param("runUuid", runUuid)
                .query((rs, rowNum) -> new RunStepRow(
                        rs.getObject("uuid", UUID.class), rs.getString("adim_kodu"),
                        rs.getString("tur_kodu"), rs.getInt("sira_no"), rs.getString("ad"),
                        rs.getString("durum_kodu"), rs.getString("baglanti_rolu"),
                        rs.getString("risk_kodu"),
                        rs.getObject("baslama_zamani", OffsetDateTime.class),
                        rs.getObject("bitis_zamani", OffsetDateTime.class),
                        rs.getObject("satir_sayisi", Long.class),
                        rs.getObject("bayt_sayisi", Long.class), rs.getString("hata_kodu")))
                .list();
    }

    @Override
    public RunRow cancelQueued(RunRow run, Actor actor, UUID eventUuid) {
        long nextEvent = run.lastEventNumber() + 1;
        int updated = jdbc.sql("""
                        update entegrasyon.calistirma_durumu
                           set durum_kodu = 'IPTAL',
                               son_olay_no = :eventNumber,
                               bitis_zamani = current_timestamp,
                               guncellenme_zamani = current_timestamp,
                               guncelleyen_kullanici_id = :actorId,
                               versiyon_no = versiyon_no + 1
                         where id = :stateId and durum_kodu = 'BEKLIYOR'
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
        return jdbc.sql("select proje_id from entegrasyon.calistirma where id = :runId")
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

    private void insertEvent(
            long projectId,
            long runId,
            long eventNumber,
            String type,
            JsonNode data,
            UUID eventUuid,
            long actorId) {
        jdbc.sql("""
                        insert into entegrasyon.calistirma_olayi(
                            proje_id, calistirma_id, olay_no, tur_kodu,
                            olay_zamani, veri_surumu, veri, uuid,
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
                       d.durum_kodu, r.yayin_ozeti, r.plan_ozeti, d.son_olay_no,
                       r.olusturulma_zamani, d.baslama_zamani, d.bitis_zamani,
                       d.iptal_isteme_zamani
                  from entegrasyon.calistirma r
                  join entegrasyon.is_talebi j on j.id = r.is_talebi_id
                  join entegrasyon.yayin y on y.id = j.yayin_id
                  join entegrasyon.calistirma_durumu d on d.calistirma_id = r.id
                  join entegrasyon.proje p on p.id = r.proje_id
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
