package tr.com.innova.akis.publication;

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

import tr.com.innova.akis.publication.PublicationModels.ApprovalActor;
import tr.com.innova.akis.publication.PublicationModels.ApprovalRow;
import tr.com.innova.akis.publication.PublicationModels.PublicationContext;
import tr.com.innova.akis.publication.PublicationModels.PublicationDraft;
import tr.com.innova.akis.publication.PublicationModels.PublicationRow;
import tr.com.innova.akis.publication.PublicationModels.ResolvedBinding;

@Repository
public class JdbcPublicationStore implements PublicationStore {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcPublicationStore(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean projectExists(UUID projectUuid) {
        return jdbc.sql("""
                        select exists(
                            select 1 from entegrasyon.proje
                             where uuid = :projectUuid and durum_kodu = 'AKTIF')
                        """)
                .param("projectUuid", projectUuid)
                .query(Boolean.class)
                .single();
    }

    @Override
    public Optional<PublicationContext> lockContext(
            UUID projectUuid, UUID scenarioUuid, UUID environmentUuid) {
        return jdbc.sql("""
                        select p.id as project_id, s.id as scenario_id,
                               s.uuid as scenario_uuid, t.uuid as definition_uuid,
                               v.uuid as definition_version_uuid,
                               v.sema_surumu as definition_schema_version,
                               v.icerik_ozeti as definition_content_hash,
                               s.plan_ozeti, s.plan as scenario_plan,
                               o.id as environment_id, o.uuid as environment_uuid,
                               o.kod as environment_code, o.risk_kodu,
                               o.politika_surumu, o.politika
                          from entegrasyon.senaryo s
                          join entegrasyon.tanim_surumu v on v.id = s.tanim_surumu_id
                          join entegrasyon.tanim t on t.id = v.tanim_id
                          join entegrasyon.proje p on p.id = t.proje_id
                          join entegrasyon.ortam o on o.proje_id = p.id
                         where p.uuid = :projectUuid
                           and p.durum_kodu = 'AKTIF'
                           and t.durum_kodu = 'AKTIF'
                           and s.uuid = :scenarioUuid
                           and o.uuid = :environmentUuid
                           and o.durum_kodu = 'AKTIF'
                         for update of s, o
                        """)
                .param("projectUuid", projectUuid)
                .param("scenarioUuid", scenarioUuid)
                .param("environmentUuid", environmentUuid)
                .query((rs, rowNum) -> new PublicationContext(
                        rs.getLong("project_id"), rs.getLong("scenario_id"),
                        rs.getObject("scenario_uuid", UUID.class),
                        rs.getObject("definition_uuid", UUID.class),
                        rs.getObject("definition_version_uuid", UUID.class),
                        rs.getInt("definition_schema_version"),
                        rs.getString("definition_content_hash"),
                        rs.getString("plan_ozeti"), json(rs.getString("scenario_plan")),
                        rs.getLong("environment_id"),
                        rs.getObject("environment_uuid", UUID.class),
                        rs.getString("environment_code"), rs.getString("risk_kodu"),
                        rs.getInt("politika_surumu"), json(rs.getString("politika"))))
                .optional();
    }

    @Override
    public List<ResolvedBinding> resolveBindings(PublicationContext context) {
        return jdbc.sql("""
                        select tvn.id as definition_data_object_id,
                               tvn.uuid as definition_data_object_uuid,
                               tvn.dugum_kodu, tvn.rol_kodu,
                               vn.uuid as data_object_uuid, vn.nesne_referansi,
                               vn.tur_kodu as data_object_type,
                               vn.durum_kodu as data_object_status,
                               m.durum_kodu as model_status,
                               ms.durum_kodu as logical_schema_status,
                               ose.id as environment_binding_id,
                               ose.uuid as environment_binding_uuid,
                               ose.versiyon_no as binding_version,
                               fs.id as physical_schema_id,
                               fs.uuid as physical_schema_uuid,
                               fs.sema_referansi,
                               bs.id as connection_version_id,
                               bs.uuid as connection_version_uuid,
                               b.veritabani_turu as database_type,
                               b.durum_kodu as connection_status,
                               target_snapshot.id as target_snapshot_id,
                               target_snapshot.uuid as target_snapshot_uuid,
                               target_snapshot.parmak_izi as target_snapshot_fingerprint
                          from entegrasyon.tanim_veri_nesnesi tvn
                          join entegrasyon.veri_nesnesi vn
                            on vn.proje_id = tvn.proje_id
                           and vn.id = tvn.veri_nesnesi_id
                           join entegrasyon.model m
                            on m.proje_id = vn.proje_id
                            and m.id = vn.model_id
                          join entegrasyon.mantiksal_sema ms
                            on ms.proje_id = m.proje_id
                           and ms.id = m.mantiksal_sema_id
                          left join entegrasyon.ortam_sema_eslemesi ose
                            on ose.proje_id = tvn.proje_id
                           and ose.mantiksal_sema_id = m.mantiksal_sema_id
                           and ose.ortam_id = :environmentId
                           and ose.durum_kodu = 'AKTIF'
                          left join entegrasyon.fiziksel_sema fs
                            on fs.proje_id = ose.proje_id
                           and fs.id = ose.fiziksel_sema_id
                           and fs.durum_kodu = 'AKTIF'
                          left join entegrasyon.baglanti_surumu bs
                            on bs.proje_id = ose.proje_id
                           and bs.id = ose.baglanti_surumu_id
                           left join entegrasyon.baglanti b
                             on b.proje_id = bs.proje_id
                            and b.id = bs.baglanti_id
                            and b.id = fs.baglanti_id
                          left join lateral (
                              select sg.id, sg.uuid, sg.parmak_izi
                                from entegrasyon.sema_goruntusu sg
                               where sg.proje_id = tvn.proje_id
                                 and sg.veri_nesnesi_id = tvn.veri_nesnesi_id
                                 and sg.fiziksel_sema_id = ose.fiziksel_sema_id
                                 and sg.baglanti_surumu_id = ose.baglanti_surumu_id
                               order by sg.kesif_zamani desc, sg.id desc
                               limit 1
                          ) target_snapshot on true
                         where tvn.proje_id = :projectId
                           and tvn.tanim_surumu_id = (
                               select tanim_surumu_id
                                 from entegrasyon.senaryo
                                where id = :scenarioId)
                         order by tvn.dugum_kodu
                        """)
                .param("projectId", context.projectId())
                .param("scenarioId", context.scenarioId())
                .param("environmentId", context.environmentId())
                .query(this::mapBinding)
                .list();
    }

    @Override
    public Optional<PublicationRow> findByReleaseHash(
            long scenarioId, long environmentId, String releaseHash) {
        return jdbc.sql(publicationSelect() + """
                         where y.senaryo_id = :scenarioId
                           and y.ortam_id = :environmentId
                           and y.fiziksel_manifesto ->> 'releaseHash' = :releaseHash
                         order by y.yayin_no desc
                         limit 1
                        """)
                .param("scenarioId", scenarioId)
                .param("environmentId", environmentId)
                .param("releaseHash", releaseHash)
                .query(this::mapPublication)
                .optional();
    }

    @Override
    public PublicationRow create(PublicationDraft draft, UUID publicationUuid) {
        Integer publicationNumber = jdbc.sql("""
                        select coalesce(max(yayin_no), 0) + 1
                          from entegrasyon.yayin
                         where senaryo_id = :scenarioId
                           and ortam_id = :environmentId
                        """)
                .param("scenarioId", draft.context().scenarioId())
                .param("environmentId", draft.context().environmentId())
                .query(Integer.class)
                .single();

        long publicationId = jdbc.sql("""
                        insert into entegrasyon.yayin(
                            proje_id, senaryo_id, ortam_id, yayin_no, durum_kodu,
                            bagimlilik_ozeti, fiziksel_manifesto, yayin_zamani, uuid)
                        values (:projectId, :scenarioId, :environmentId,
                                :publicationNumber, :status, :dependencySummary,
                                cast(:physicalManifest as jsonb),
                                case when :status = 'AKTIF' then current_timestamp else null end,
                                :uuid)
                        returning id
                        """)
                .param("projectId", draft.context().projectId())
                .param("scenarioId", draft.context().scenarioId())
                .param("environmentId", draft.context().environmentId())
                .param("publicationNumber", publicationNumber)
                .param("status", draft.status())
                .param("dependencySummary", draft.dependencySummary())
                .param("physicalManifest", draft.physicalManifest().toString())
                .param("uuid", publicationUuid)
                .query(Long.class)
                .single();

        for (ResolvedBinding binding : draft.bindings()) {
            jdbc.sql("""
                            insert into entegrasyon.yayin_veri_bagi(
                                proje_id, yayin_id, tanim_veri_nesnesi_id,
                                ortam_sema_eslemesi_id, fiziksel_sema_id,
                                baglanti_surumu_id, sema_goruntusu_id,
                                fiziksel_kimlik, bag_versiyon_no)
                            values (:projectId, :publicationId, :definitionDataObjectId,
                                    :environmentBindingId, :physicalSchemaId,
                                    :connectionVersionId, :targetSnapshotId,
                                    :physicalIdentity, :bindingVersion)
                            """)
                    .param("projectId", draft.context().projectId())
                    .param("publicationId", publicationId)
                    .param("definitionDataObjectId", binding.definitionDataObjectId())
                    .param("environmentBindingId", binding.environmentSchemaBindingId())
                    .param("physicalSchemaId", binding.physicalSchemaId())
                    .param("connectionVersionId", binding.connectionVersionId())
                    .param("targetSnapshotId", binding.targetSnapshotId())
                    .param("physicalIdentity", physicalIdentity(binding))
                    .param("bindingVersion", binding.bindingVersion())
                    .update();
        }
        return findById(publicationId).orElseThrow();
    }

    @Override
    public Optional<PublicationRow> find(UUID projectUuid, UUID publicationUuid) {
        return jdbc.sql(publicationSelect() + """
                         where p.uuid = :projectUuid
                           and y.uuid = :publicationUuid
                        """)
                .param("projectUuid", projectUuid)
                .param("publicationUuid", publicationUuid)
                .query(this::mapPublication)
                .optional();
    }

    @Override
    public List<PublicationRow> list(UUID projectUuid) {
        return jdbc.sql(publicationSelect() + """
                         where p.uuid = :projectUuid
                         order by y.olusturulma_zamani desc, y.id desc
                        """)
                .param("projectUuid", projectUuid)
                .query(this::mapPublication)
                .list();
    }

    @Override
    public Optional<PublicationRow> lockPublication(
            UUID projectUuid, UUID publicationUuid) {
        return jdbc.sql(publicationSelect() + """
                         where p.uuid = :projectUuid
                           and y.uuid = :publicationUuid
                         for update of y
                        """)
                .param("projectUuid", projectUuid)
                .param("publicationUuid", publicationUuid)
                .query(this::mapPublication)
                .optional();
    }

    @Override
    public Optional<ApprovalActor> findActiveActor(String provider, String subject) {
        return jdbc.sql("""
                        select id, uuid, ad
                          from entegrasyon.kullanici
                         where oidc_saglayici = :provider
                           and oidc_ozne = :subject
                           and durum_kodu = 'AKTIF'
                        """)
                .param("provider", provider)
                .param("subject", subject)
                .query((rs, rowNum) -> new ApprovalActor(
                        rs.getLong("id"), rs.getObject("uuid", UUID.class),
                        rs.getString("ad")))
                .optional();
    }

    @Override
    public Optional<ApprovalRow> findLatestApproval(
            long publicationId, long actorId, String decision) {
        return jdbc.sql(approvalSelect() + """
                         where yo.yayin_id = :publicationId
                           and yo.kullanici_id = :actorId
                           and yo.karar_kodu = :decision
                         order by yo.karar_zamani desc, yo.id desc
                         limit 1
                        """)
                .param("publicationId", publicationId)
                .param("actorId", actorId)
                .param("decision", decision)
                .query(this::mapApproval)
                .optional();
    }

    @Override
    public ApprovalRow createApproval(
            PublicationRow publication,
            ApprovalActor actor,
            String decision,
            String reason,
            UUID approvalUuid) {
        jdbc.sql("""
                        insert into entegrasyon.yayin_onayi(
                            proje_id, yayin_id, kullanici_id, plan_ozeti,
                            karar_kodu, karar_zamani, gerekce, uuid,
                            olusturan_kullanici_id)
                        select y.proje_id, y.id, :actorId, :planSummary,
                               :decision, current_timestamp, :reason, :uuid, :actorId
                          from entegrasyon.yayin y
                         where y.id = :publicationId
                        """)
                .param("publicationId", publication.id())
                .param("actorId", actor.id())
                .param("planSummary", publication.releaseHash())
                .param("decision", decision)
                .param("reason", reason)
                .param("uuid", approvalUuid)
                .update();
        return findLatestApproval(publication.id(), actor.id(), decision).orElseThrow();
    }

    @Override
    public PublicationRow transition(
            long publicationId, String expectedStatus, String targetStatus) {
        int updated = jdbc.sql("""
                        update entegrasyon.yayin
                           set durum_kodu = :targetStatus,
                               yayin_zamani = case
                                   when :targetStatus = 'AKTIF' then current_timestamp
                                   else yayin_zamani
                               end,
                               guncellenme_zamani = current_timestamp,
                               versiyon_no = versiyon_no + 1
                         where id = :publicationId
                           and durum_kodu = :expectedStatus
                        """)
                .param("publicationId", publicationId)
                .param("expectedStatus", expectedStatus)
                .param("targetStatus", targetStatus)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Publication status transition lost its lock.");
        }
        return findById(publicationId).orElseThrow();
    }

    private Optional<PublicationRow> findById(long publicationId) {
        return jdbc.sql(publicationSelect() + " where y.id = :publicationId")
                .param("publicationId", publicationId)
                .query(this::mapPublication)
                .optional();
    }

    private String publicationSelect() {
        return """
                select y.id, y.uuid, s.uuid as scenario_uuid,
                       t.uuid as definition_uuid, v.uuid as definition_version_uuid,
                       o.uuid as environment_uuid, o.kod as environment_code,
                       o.risk_kodu, y.yayin_no, y.durum_kodu,
                       y.fiziksel_manifesto ->> 'releaseHash' as release_hash,
                       y.bagimlilik_ozeti, y.fiziksel_manifesto,
                       y.yayin_zamani, y.olusturulma_zamani, y.versiyon_no
                  from entegrasyon.yayin y
                  join entegrasyon.proje p on p.id = y.proje_id
                  join entegrasyon.senaryo s on s.id = y.senaryo_id
                  join entegrasyon.tanim_surumu v on v.id = s.tanim_surumu_id
                  join entegrasyon.tanim t on t.id = v.tanim_id
                  join entegrasyon.ortam o
                    on o.proje_id = y.proje_id and o.id = y.ortam_id
                """;
    }

    private String approvalSelect() {
        return """
                select yo.uuid, y.uuid as publication_uuid, k.uuid as actor_uuid,
                       k.ad as actor_name, yo.karar_kodu, yo.karar_zamani, yo.gerekce
                  from entegrasyon.yayin_onayi yo
                  join entegrasyon.yayin y on y.id = yo.yayin_id
                  join entegrasyon.kullanici k on k.id = yo.kullanici_id
                """;
    }

    private ResolvedBinding mapBinding(ResultSet rs, int rowNum) throws SQLException {
        Long environmentBindingId = rs.getObject("environment_binding_id", Long.class);
        Long bindingVersion = rs.getObject("binding_version", Long.class);
        return new ResolvedBinding(
                rs.getLong("definition_data_object_id"),
                rs.getObject("definition_data_object_uuid", UUID.class),
                rs.getString("dugum_kodu"), rs.getString("rol_kodu"),
                rs.getObject("data_object_uuid", UUID.class),
                rs.getString("nesne_referansi"), rs.getString("data_object_type"),
                environmentBindingId,
                rs.getObject("environment_binding_uuid", UUID.class),
                rs.getObject("physical_schema_id", Long.class),
                rs.getObject("physical_schema_uuid", UUID.class),
                rs.getString("sema_referansi"),
                rs.getObject("connection_version_id", Long.class),
                rs.getObject("connection_version_uuid", UUID.class),
                rs.getString("database_type"),
                rs.getObject("target_snapshot_id", Long.class),
                rs.getObject("target_snapshot_uuid", UUID.class),
                rs.getString("target_snapshot_fingerprint"),
                bindingVersion == null ? 0 : bindingVersion,
                rs.getString("data_object_status"),
                rs.getString("model_status"),
                rs.getString("logical_schema_status"),
                rs.getString("connection_status"));
    }

    private PublicationRow mapPublication(ResultSet rs, int rowNum) throws SQLException {
        return new PublicationRow(
                rs.getLong("id"), rs.getObject("uuid", UUID.class),
                rs.getObject("scenario_uuid", UUID.class),
                rs.getObject("definition_uuid", UUID.class),
                rs.getObject("definition_version_uuid", UUID.class),
                rs.getObject("environment_uuid", UUID.class),
                rs.getString("environment_code"), rs.getString("risk_kodu"),
                rs.getInt("yayin_no"), rs.getString("durum_kodu"),
                rs.getString("release_hash"), rs.getString("bagimlilik_ozeti"),
                json(rs.getString("fiziksel_manifesto")),
                rs.getObject("yayin_zamani", OffsetDateTime.class),
                rs.getObject("olusturulma_zamani", OffsetDateTime.class),
                rs.getLong("versiyon_no"));
    }

    private ApprovalRow mapApproval(ResultSet rs, int rowNum) throws SQLException {
        return new ApprovalRow(
                rs.getObject("uuid", UUID.class),
                rs.getObject("publication_uuid", UUID.class),
                rs.getObject("actor_uuid", UUID.class), rs.getString("actor_name"),
                rs.getString("karar_kodu"),
                rs.getObject("karar_zamani", OffsetDateTime.class),
                rs.getString("gerekce"));
    }

    private String physicalIdentity(ResolvedBinding binding) {
        return binding.physicalSchemaReference() + "." + binding.dataObjectReference();
    }

    private JsonNode json(String value) {
        try {
            return objectMapper.readTree(value);
        }
        catch (JacksonException exception) {
            throw new IllegalStateException("Stored publication JSON could not be read.", exception);
        }
    }
}
