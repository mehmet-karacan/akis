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
                            select 1 from akis.proje
                             where uuid = :projectUuid and arsivlenme_zamani is null)
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
                               o.kod as environment_code, o.risk as risk_kodu,
                               o.politika_sema_surumu as politika_surumu, o.politika
                          from akis.senaryo s
                          join akis.tanim_surumu v on v.id = s.tanim_surumu_id
                          join akis.tanim t on t.id = v.tanim_id
                          join akis.proje p on p.id = t.proje_id
                          cross join akis.ortam o
                         where p.uuid = :projectUuid
                           and p.arsivlenme_zamani is null
                           and t.arsivlenme_zamani is null
                           and s.uuid = :scenarioUuid
                           and o.uuid = :environmentUuid
                           and o.durum = 'ETKIN'
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

    /** Sensitive column names per source binding node code, from the bound data objects' column policies. */
    @Override
    public java.util.Map<String, List<String>> sensitiveColumns(PublicationContext context, List<ResolvedBinding> bindings) {
        var result = new java.util.LinkedHashMap<String, List<String>>();
        for (ResolvedBinding binding : bindings) {
            if (!"KAYNAK".equals(binding.role())) continue;
            List<String> columns = jdbc.sql("""
                    select p.kolon_adi from akis.veri_nesnesi_kolon_politikasi p
                      join akis.veri_nesnesi vn on vn.id = p.veri_nesnesi_id
                     where vn.uuid = :object and p.koruma = 'SIFRELE' order by p.kolon_adi
                    """).param("object", binding.dataObjectUuid()).query(String.class).list();
            if (!columns.isEmpty()) result.put(binding.nodeCode(), columns);
        }
        return result;
    }

    @Override
    public void verifySensitiveColumns(PublicationContext context, List<ResolvedBinding> bindings, java.util.Map<String, List<String>> sensitive) {
        JsonNode definition = context.scenarioPlan().path("executable").path("definition");
        boolean mapping = definition.has("columnMappings");
        for (var entry : sensitive.entrySet()) {
            ResolvedBinding source = bindings.stream().filter(b -> b.nodeCode().equals(entry.getKey())).findFirst().orElseThrow();
            var sourceColumns = snapshotColumns(source.targetSnapshotId());
            for (String column : entry.getValue()) {
                var sourceColumn = sourceColumns.get(column);
                if (sourceColumn == null) continue; // marked column not in the snapshot: nothing to protect
                if (!sourceColumn.text())
                    throw new tr.com.innova.akis.metadata.ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT, "SENSITIVE_COLUMN_NOT_TEXT",
                            "Hassas kolon " + source.dataObjectReference() + "." + column + " metin olmalı; " + sourceColumn.type() + " şifrelenemez.");
                int required = tr.com.innova.akis.security.DataProtectionCipher.requiredLength((int) Math.max(1, sourceColumn.length()));
                // Target column: mapping → column mapping target; procedure → same-named column on the target task bindings.
                for (ResolvedBinding target : bindings) {
                    if (!"HEDEF".equals(target.role())) continue;
                    String targetName = mapping ? mappedTarget(definition, entry.getKey(), column) : column;
                    if (targetName == null) continue;
                    var targetColumn = snapshotColumns(target.targetSnapshotId()).get(targetName);
                    if (targetColumn == null) continue;
                    // An unbounded textual target (PostgreSQL TEXT / VARCHAR without a modifier) has no capacity to check.
                    boolean unbounded = targetColumn.length() <= 0;
                    if (!targetColumn.text() || !unbounded && targetColumn.length() < required)
                        throw new tr.com.innova.akis.metadata.ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT, "SENSITIVE_TARGET_TOO_NARROW",
                                "Hassas kolon " + column + " şifreli aktarılacak; hedef " + target.dataObjectReference() + "." + targetName + " (" + targetColumn.type()
                                + ") en az " + required + " karakter taşıyabilmeli.");
                }
            }
        }
    }

    private record SnapshotColumn(String type, long length, boolean text) { }

    @Override
    public List<String> staleDesignSnapshots(PublicationContext context, List<ResolvedBinding> bindings) {
        return bindings.stream()
                .filter(binding -> binding.targetSnapshotId() != null)
                .filter(binding -> {
                    Long designed = jdbc.sql("select sema_goruntusu_id from akis.tanim_veri_nesnesi where id = :id")
                            .param("id", binding.definitionDataObjectId()).query(Long.class).optional().orElse(null);
                    return designed != null && !designed.equals(binding.targetSnapshotId());
                })
                .map(ResolvedBinding::nodeCode)
                .toList();
    }

    private java.util.Map<String, SnapshotColumn> snapshotColumns(Long snapshotId) {
        var columns = new java.util.HashMap<String, SnapshotColumn>();
        if (snapshotId == null) return columns;
        jdbc.sql("select ad, uretici_tipi, coalesce(uzunluk, 0) as uzunluk from akis.kolon_goruntusu where sema_goruntusu_id = :snapshot")
                .param("snapshot", snapshotId).query((rs, n) -> {
                    String type = rs.getString("uretici_tipi").toUpperCase(java.util.Locale.ROOT);
                    // Textual across technologies: Oracle VARCHAR2/NVARCHAR2, PostgreSQL VARCHAR/TEXT (unbounded = no capacity limit).
                    boolean text = type.startsWith("VARCHAR2") || type.startsWith("NVARCHAR2") || type.startsWith("VARCHAR") || type.equals("TEXT");
                    columns.put(rs.getString("ad").toUpperCase(java.util.Locale.ROOT), new SnapshotColumn(type, rs.getLong("uzunluk"), text));
                    return null;
                }).list();
        return columns;
    }

    private static String mappedTarget(JsonNode definition, String sourceObject, String sourceColumn) {
        for (JsonNode item : definition.path("columnMappings")) {
            if (sourceObject.equals(item.path("source").path("object").asText()) && sourceColumn.equalsIgnoreCase(item.path("source").path("column").asText()))
                return item.path("target").path("column").asText().toUpperCase(java.util.Locale.ROOT);
        }
        return null;
    }

    @Override
    public JsonNode resolveVariableBindings(PublicationContext context) {
        var bindings = objectMapper.createObjectNode();
        for (JsonNode task : context.scenarioPlan().path("executable").path("definition").path("tasks")) {
            for (var entry : task.path("parameters").properties()) {
                JsonNode parameter = entry.getValue();
                if (!"REFRESH_QUERY".equals(parameter.path("valueSource").asText())) continue;
                UUID definition;
                UUID logical;
                try {
                    definition = UUID.fromString(parameter.path("definitionUuid").asText());
                    logical = UUID.fromString(parameter.path("logicalSchemaUuid").asText());
                } catch (IllegalArgumentException invalid) {
                    throw new tr.com.innova.akis.metadata.ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT,
                        "VARIABLE_SCHEMA_REQUIRED", "Değişkene mantıksal şema seçin ve prosedüre yeniden ekleyin.");
                }
                String mode = parameter.path("historyMode").asText("LATEST");
                if (!java.util.Set.of("NONE", "LATEST", "ALL").contains(mode)) throw new IllegalArgumentException("Invalid variable history mode");
                var binding = jdbc.sql("""
                    select p.uuid as project_uuid, b.uuid as connection_uuid, fs.uuid as physical_uuid,
                           fs.sema_adi, ms.uuid as logical_uuid
                      from akis.proje p
                      join akis.tanim t on t.proje_id=p.id and t.uuid=:definition
                      join akis.mantiksal_sema ms on ms.uuid=:logical
                      join akis.sema_eslemesi se on se.mantiksal_sema_id=ms.id and se.ortam_id=:environment
                      join akis.fiziksel_sema fs on fs.id=se.fiziksel_sema_id
                      join akis.baglanti b on b.id=fs.baglanti_id
                     where p.id=:project and t.tur='DEGISKEN' and t.arsivlenme_zamani is null and ms.durum='ETKIN'
                       and fs.durum='ETKIN' and b.durum='ETKIN' and b.saglayici_turu='ORACLE'
                    """).param("project", context.projectId()).param("environment", context.environmentId())
                    .param("definition", definition).param("logical", logical).query((rs, n) -> {
                        var node = objectMapper.createObjectNode();
                        node.put("projectUuid", rs.getString("project_uuid"));
                        node.put("connectionVersionUuid", rs.getString("connection_uuid"));
                        node.put("physicalSchemaUuid", rs.getString("physical_uuid"));
                        node.put("owner", rs.getString("sema_adi"));
                        node.put("logicalSchemaUuid", logical.toString());
                        node.put("environmentUuid", context.environmentUuid().toString());
                        node.put("historyMode", mode);
                        node.put("query", parameter.path("query").asText());
                        node.put("type", parameter.path("type").asText());
                        return node;
                    }).optional().orElseThrow(() -> new tr.com.innova.akis.metadata.ApiException(
                        org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT, "VARIABLE_MAPPING_REQUIRED",
                        "Değişkenin mantıksal şeması için çalıştırma ortamında etkin Oracle bağlantısı bulunamadı."));
                JsonNode previous = bindings.get(definition.toString());
                if (previous != null && !previous.equals(binding)) throw new tr.com.innova.akis.metadata.ApiException(
                    org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT, "VARIABLE_CONFLICT", "Aynı değişken çelişkili tanımlarla kullanılamaz.");
                bindings.set(definition.toString(), binding);
            }
        }
        return bindings;
    }

    @Override
    public List<ResolvedBinding> resolveBindings(PublicationContext context) {
        return jdbc.sql("""
                        select tvn.id as definition_data_object_id,
                               tvn.uuid as definition_data_object_uuid,
                               tvn.dugum_kodu, tvn.rol as rol_kodu,
                               vn.uuid as data_object_uuid, vn.nesne_referansi,
                               case vn.tur when 'GORUNUM' then 'VIEW' else vn.tur end as data_object_type,
                               case when vn.arsivlenme_zamani is null then 'AKTIF' else 'PASIF' end as data_object_status,
                               case when m.arsivlenme_zamani is null then 'AKTIF' else 'ARSIV' end as model_status,
                               case when ms.durum = 'ETKIN' then 'AKTIF' else 'PASIF' end as logical_schema_status,
                               ose.id as environment_binding_id,
                               ose.uuid as environment_binding_uuid,
                               1::bigint as binding_version,
                               fs.id as physical_schema_id,
                               fs.uuid as physical_schema_uuid,
                               fs.sema_adi as sema_referansi,
                               b.id as connection_version_id,
                               b.uuid as connection_version_uuid,
                               b.saglayici_turu as database_type,
                               case when b.durum = 'ETKIN' then 'AKTIF' else 'PASIF' end as connection_status,
                               target_snapshot.id as target_snapshot_id,
                               target_snapshot.uuid as target_snapshot_uuid,
                               target_snapshot.parmak_izi as target_snapshot_fingerprint
                          from akis.tanim_veri_nesnesi tvn
                          join akis.veri_nesnesi vn
                            on vn.proje_id = tvn.proje_id
                           and vn.id = tvn.veri_nesnesi_id
                           join akis.model m
                            on m.proje_id = vn.proje_id
                            and m.id = vn.model_id
                          join akis.mantiksal_sema ms
                            on ms.id = m.mantiksal_sema_id
                          left join akis.sema_eslemesi ose
                            on ose.mantiksal_sema_id = m.mantiksal_sema_id
                           and ose.ortam_id = :environmentId
                          left join akis.fiziksel_sema fs
                            on fs.id = ose.fiziksel_sema_id
                           and fs.durum = 'ETKIN'
                          left join akis.baglanti b
                            on b.id = fs.baglanti_id
                          left join lateral (
                              select sg.id, sg.uuid, sg.parmak_izi
                                from akis.sema_goruntusu sg
                               where sg.proje_id = tvn.proje_id
                                 and sg.veri_nesnesi_id = tvn.veri_nesnesi_id
                                 and sg.fiziksel_sema_id = ose.fiziksel_sema_id
                               order by sg.kesif_zamani desc, sg.id desc
                               limit 1
                          ) target_snapshot on true
                         where tvn.proje_id = :projectId
                           and tvn.tanim_surumu_id = (
                               select tanim_surumu_id
                                 from akis.senaryo
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
                          from akis.yayin
                         where senaryo_id = :scenarioId
                           and ortam_id = :environmentId
                        """)
                .param("scenarioId", draft.context().scenarioId())
                .param("environmentId", draft.context().environmentId())
                .query(Integer.class)
                .single();

        long publicationId = jdbc.sql("""
                        insert into akis.yayin(
                            proje_id, senaryo_id, ortam_id, yayin_no, durum,
                            bagimlilik_ozeti, fiziksel_manifesto, etkinlestirilme_zamani, uuid)
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
                            insert into akis.yayin_veri_bagi(
                                proje_id, yayin_id, tanim_veri_nesnesi_id,
                                sema_eslemesi_id, fiziksel_sema_id, sema_goruntusu_id,
                                fiziksel_kimlik, bag_surumu)
                            values (:projectId, :publicationId, :definitionDataObjectId,
                                    :environmentBindingId, :physicalSchemaId, :targetSnapshotId,
                                    :physicalIdentity, :bindingVersion)
                            """)
                    .param("projectId", draft.context().projectId())
                    .param("publicationId", publicationId)
                    .param("definitionDataObjectId", binding.definitionDataObjectId())
                    .param("environmentBindingId", binding.environmentSchemaBindingId())
                    .param("physicalSchemaId", binding.physicalSchemaId())
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
                        select k.id, k.uuid, k.gorunen_ad as ad
                          from akis.kullanici k
                          join akis.harici_kimlik h on h.kullanici_id = k.id
                         where ((:provider = 'LOCAL_BASIC'
                                  and h.saglayici_turu = 'YEREL' and h.yayinlayici is null)
                                or (h.saglayici_turu = 'OIDC' and h.yayinlayici = :provider))
                           and h.harici_kullanici_anahtari = :subject
                           and k.devre_disi_birakilma_zamani is null
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
                           and yo.karar = :decision
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
                        insert into akis.yayin_onayi(
                            proje_id, yayin_id, kullanici_id, plan_ozeti,
                            karar, karar_zamani, gerekce, uuid,
                            olusturan_kullanici_id)
                        select y.proje_id, y.id, :actorId, :planSummary,
                               :decision, current_timestamp, :reason, :uuid, :actorId
                          from akis.yayin y
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
                        update akis.yayin
                           set durum = :targetStatus,
                               etkinlestirilme_zamani = case
                                   when :targetStatus = 'AKTIF' then current_timestamp
                                   else etkinlestirilme_zamani
                               end,
                               guncellenme_zamani = current_timestamp,
                               versiyon_no = versiyon_no + 1
                         where id = :publicationId
                           and durum = :expectedStatus
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
                       o.risk as risk_kodu, y.yayin_no, y.durum as durum_kodu,
                       y.fiziksel_manifesto ->> 'releaseHash' as release_hash,
                       y.bagimlilik_ozeti, y.fiziksel_manifesto,
                       y.etkinlestirilme_zamani as yayin_zamani, y.olusturulma_zamani, y.versiyon_no
                  from akis.yayin y
                  join akis.proje p on p.id = y.proje_id
                  join akis.senaryo s on s.id = y.senaryo_id
                  join akis.tanim_surumu v on v.id = s.tanim_surumu_id
                  join akis.tanim t on t.id = v.tanim_id
                  join akis.ortam o
                    on o.id = y.ortam_id
                """;
    }

    private String approvalSelect() {
        return """
                select yo.uuid, y.uuid as publication_uuid, k.uuid as actor_uuid,
                       k.gorunen_ad as actor_name, yo.karar as karar_kodu, yo.karar_zamani, yo.gerekce
                  from akis.yayin_onayi yo
                  join akis.yayin y on y.id = yo.yayin_id
                  join akis.kullanici k on k.id = yo.kullanici_id
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
