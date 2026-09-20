package tr.com.innova.akis.execution;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.discovery.SchemaFingerprint;
import tr.com.innova.akis.discovery.SchemaFingerprintInput;
import tr.com.innova.akis.discovery.SchemaFingerprintInput.Column;
import tr.com.innova.akis.discovery.SchemaFingerprintInput.Constraint;
import tr.com.innova.akis.execution.PilotRuntimePlan.DatasetBinding;
import tr.com.innova.akis.execution.PilotRuntimePlan.DatasetRole;
import tr.com.innova.akis.execution.PinnedSchemaSnapshotException.Failure;
import tr.com.innova.akis.execution.PinnedSchemaSnapshotPort.PinnedSnapshot;
import tr.com.innova.akis.execution.PinnedSchemaSnapshotPort.PinnedSnapshots;

/** PostgreSQL adapter for the schema bodies frozen by a published binding. */
@Repository
public class JdbcPinnedSchemaSnapshotStore implements PinnedSchemaSnapshotPort {

    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

    private static final String HEADER_SQL = """
            select p.uuid as project_uuid,
                   y.uuid as publication_uuid,
                   sg.id as snapshot_id,
                   sg.uuid as snapshot_uuid,
                   sg.parmak_izi,
                   sg.motor_surumu,
                   sg.ozellik_sema_surumu as ozellik_surumu,
                   sg.ozellik
              from akis.yayin_veri_bagi yb
              join akis.yayin y
                on y.proje_id = yb.proje_id and y.id = yb.yayin_id
              join akis.proje p on p.id = yb.proje_id
              join akis.senaryo sn on sn.id = y.senaryo_id
              join akis.tanim_surumu ts on ts.id = sn.tanim_surumu_id
              join akis.tanim t on t.id = ts.tanim_id
              join akis.tanim_veri_nesnesi tvn
                on tvn.proje_id = yb.proje_id
               and tvn.id = yb.tanim_veri_nesnesi_id
              join akis.veri_nesnesi vn
                on vn.proje_id = yb.proje_id and vn.id = tvn.veri_nesnesi_id
              join akis.model m
                on m.proje_id = yb.proje_id and m.id = vn.model_id
              join akis.sema_eslemesi ose
                on ose.id = yb.sema_eslemesi_id
              join akis.ortam o
                on o.id = ose.ortam_id
              join akis.mantiksal_sema ms
                on ms.id = ose.mantiksal_sema_id
              join akis.fiziksel_sema fs
                on fs.id = yb.fiziksel_sema_id
              join akis.baglanti b
                on b.id = fs.baglanti_id
              join akis.sema_goruntusu sg
                on sg.proje_id = yb.proje_id and sg.id = yb.sema_goruntusu_id
              join akis.sema_goruntusu_oracle_kaniti ok
                on ok.proje_id = yb.proje_id
               and ok.sema_goruntusu_id = sg.id
             where t.uuid = :definitionUuid
               and ts.uuid = :definitionVersionUuid
               and (y.fiziksel_manifesto ->> 'releaseHash') = :releaseHash
               and tvn.uuid = :definitionDataObjectUuid
               and tvn.dugum_kodu = :datasetId
               and tvn.rol = :storedRole
               and tvn.tanim_surumu_id = ts.id
               and vn.uuid = :dataObjectUuid
               and vn.tur = 'TABLO'
               and vn.arsivlenme_zamani is null
               and m.arsivlenme_zamani is null
               and vn.nesne_referansi = :objectName
               and ose.uuid = :environmentSchemaBindingUuid
               and ose.ortam_id = y.ortam_id
               and o.durum = 'ETKIN'
               and ms.durum = 'ETKIN'
               and yb.fiziksel_sema_id = ose.fiziksel_sema_id
               and fs.uuid = :physicalSchemaUuid
               and fs.sema_adi = :owner
               and fs.durum = 'ETKIN'
               and b.uuid = :connectionVersionUuid
               and b.saglayici_turu in ('ORACLE', 'POSTGRESQL')
               and b.durum = 'ETKIN'
               and y.durum = :publicationStatus
               and sg.uuid = :schemaSnapshotUuid
               and sg.id = tvn.sema_goruntusu_id
               and sg.veri_nesnesi_id = vn.id
               and sg.fiziksel_sema_id = fs.id
               and sg.baglanti_id = b.id
               and sg.parmak_izi = :snapshotFingerprint
               and yb.fiziksel_kimlik = :physicalIdentity
               and yb.bag_surumu = :bindingVersion
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final SchemaFingerprint fingerprint;

    public JdbcPinnedSchemaSnapshotStore(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.fingerprint = new SchemaFingerprint(objectMapper);
    }

    @Override
    @Transactional(readOnly = true)
    public PinnedSnapshots load(MappingExecutionContract plan) {
        validatePlan(plan);
        try {
            LoadedSnapshot source = loadBinding(
                    plan, plan.source(), DatasetRole.SOURCE, "AKTIF");
            LoadedSnapshot target = loadBinding(
                    plan, plan.target(), DatasetRole.TARGET, "AKTIF");
            if (!source.projectUuid().equals(target.projectUuid())
                    || !source.publicationUuid().equals(target.publicationUuid())) {
                throw failure(Failure.CROSS_PUBLICATION_BINDING);
            }
            Map<String, PinnedSnapshot> sourceSnapshots = new LinkedHashMap<>();
            sourceSnapshots.put(plan.source().datasetId(), source.snapshot());
            if (plan instanceof StagedRuntimePlan staged) {
                if (staged.sources().isEmpty() || !staged.sources().getFirst().equals(plan.source())
                        || staged.sources().stream().map(DatasetBinding::datasetId).distinct().count()!=staged.sources().size())
                    throw failure(Failure.INVALID_CONTRACT);
                for (DatasetBinding binding : staged.sources().subList(1, staged.sources().size())) {
                    if (!binding.connectionVersionUuid().equals(plan.source().connectionVersionUuid()))
                        throw failure(Failure.INVALID_CONTRACT);
                    LoadedSnapshot additional = loadBinding(plan, binding, DatasetRole.SOURCE, "AKTIF");
                    if (!source.projectUuid().equals(additional.projectUuid())
                            || !source.publicationUuid().equals(additional.publicationUuid()))
                        throw failure(Failure.CROSS_PUBLICATION_BINDING);
                    sourceSnapshots.put(binding.datasetId(), additional.snapshot());
                }
            }
            return new PinnedSnapshots(
                    source.projectUuid(), source.publicationUuid(),
                    source.snapshot(), target.snapshot(), sourceSnapshots);
        }
        catch (PinnedSchemaSnapshotException exception) {
            throw exception;
        }
        catch (RuntimeException exception) {
            // Do not retain JDBC/JSON causes: messages can contain endpoints or data.
            throw failure(Failure.METADATA_UNAVAILABLE);
        }
    }

    private LoadedSnapshot loadBinding(
            MappingExecutionContract plan,
            DatasetBinding binding,
            DatasetRole role,
            String publicationStatus) {
        validateBinding(binding, role);
        List<SnapshotHeader> matches = jdbc.sql(HEADER_SQL)
                .param("definitionUuid", plan.definitionUuid())
                .param("definitionVersionUuid", plan.definitionVersionUuid())
                .param("releaseHash", plan.releaseHash())
                .param("definitionDataObjectUuid", binding.definitionDataObjectUuid())
                .param("datasetId", binding.datasetId())
                .param("storedRole", role == DatasetRole.SOURCE ? "KAYNAK" : "HEDEF")
                .param("dataObjectUuid", binding.dataObjectUuid())
                .param("objectName", binding.objectName())
                .param("environmentSchemaBindingUuid", binding.environmentSchemaBindingUuid())
                .param("physicalSchemaUuid", binding.physicalSchemaUuid())
                .param("owner", binding.owner())
                .param("connectionVersionUuid", binding.connectionVersionUuid())
                .param("schemaSnapshotUuid", binding.schemaSnapshotUuid())
                .param("snapshotFingerprint", binding.schemaSnapshotFingerprint())
                .param("physicalIdentity", binding.physicalIdentity())
                .param("bindingVersion", binding.bindingVersion())
                .param("publicationStatus", publicationStatus)
                .query(this::mapHeader)
                .list();
        if (matches.isEmpty()) {
            throw failure(Failure.BINDING_NOT_FOUND);
        }
        if (matches.size() != 1) {
            throw failure(Failure.AMBIGUOUS_BINDING);
        }
        SnapshotHeader header = matches.getFirst();
        SchemaFingerprintInput body = new SchemaFingerprintInput(
                header.engineVersion(), header.propertyVersion(), header.properties(),
                loadColumns(header.snapshotId()), loadConstraints(header.snapshotId()));
        String calculated;
        try {
            calculated = fingerprint.calculate(body);
        }
        catch (RuntimeException exception) {
            throw failure(Failure.STORED_BODY_INVALID);
        }
        if (!constantTimeEquals(header.fingerprint(), calculated)
                || !constantTimeEquals(binding.schemaSnapshotFingerprint(), calculated)) {
            throw failure(Failure.FINGERPRINT_MISMATCH);
        }
        return new LoadedSnapshot(
                header.projectUuid(), header.publicationUuid(),
                new PinnedSnapshot(header.snapshotUuid(), calculated, body));
    }

    /**
     * Loads the exact trusted source snapshot for a Procedure publication.
     * Pending approval is accepted only because this method is used by the
     * source-only preflight path; it does not create a runnable execution.
     */
    @Transactional(readOnly = true)
    PinnedProcedureSource loadProcedureSource(
            ProcedureRuntimePlan plan,
            ProcedureRuntimePlan.Task sourceTask,
            ProcedureRuntimePlan.TaskBinding binding,
            String publicationStatus) {
        if (plan == null || sourceTask == null || binding == null
                || sourceTask.connectionRole() != ProcedureRuntimePlan.ConnectionRole.SOURCE
                || binding.role() != ProcedureRuntimePlan.ConnectionRole.SOURCE
                || !sourceTask.id().equals(binding.taskId())
                || sourceTask.output() == null
                || !("AKTIF".equals(publicationStatus)
                    || "ONAY_BEKLIYOR".equals(publicationStatus))) {
            throw failure(Failure.INVALID_CONTRACT);
        }
        DatasetBinding adapted = ProcedureOracleBindingAdapter.source(binding);
        PilotRuntimePlan adaptedPlan = new PilotRuntimePlan(
                PilotRuntimePlan.CURRENT_VERSION,
                plan.runtimePlanHash(),
                plan.releaseHash(),
                plan.scenarioPlanHash(),
                plan.definitionUuid(),
                plan.definitionVersionUuid(),
                sourceTask.output().maximumRows(),
                adapted,
                null,
                List.of(),
                PilotRuntimePlan.WriteStrategy.ATOMIC_DELETE_INSERT,
                plan.canonicalPlan());
        LoadedSnapshot loaded = loadBinding(
                adaptedPlan, adapted, DatasetRole.SOURCE, publicationStatus);
        return new PinnedProcedureSource(
                loaded.projectUuid(), loaded.publicationUuid(), loaded.snapshot());
    }

    @Transactional(readOnly = true)
    PinnedProcedureTarget loadProcedureTarget(
            ProcedureRuntimePlan plan,
            ProcedureRuntimePlan.Task targetTask,
            ProcedureRuntimePlan.TaskBinding binding,
            String publicationStatus) {
        if (plan == null || targetTask == null || binding == null
                || targetTask.connectionRole() != ProcedureRuntimePlan.ConnectionRole.TARGET
                || binding.role() != ProcedureRuntimePlan.ConnectionRole.TARGET
                || !targetTask.id().equals(binding.taskId())
                || !("AKTIF".equals(publicationStatus)
                    || "ONAY_BEKLIYOR".equals(publicationStatus))) {
            throw failure(Failure.INVALID_CONTRACT);
        }
        DatasetBinding adapted = ProcedureOracleBindingAdapter.target(binding);
        PilotRuntimePlan adaptedPlan = new PilotRuntimePlan(
                PilotRuntimePlan.CURRENT_VERSION,
                plan.runtimePlanHash(),
                plan.releaseHash(),
                plan.scenarioPlanHash(),
                plan.definitionUuid(),
                plan.definitionVersionUuid(),
                1,
                null,
                adapted,
                List.of(),
                PilotRuntimePlan.WriteStrategy.ATOMIC_DELETE_INSERT,
                plan.canonicalPlan());
        LoadedSnapshot loaded = loadBinding(
                adaptedPlan, adapted, DatasetRole.TARGET, publicationStatus);
        return new PinnedProcedureTarget(
                loaded.projectUuid(), loaded.publicationUuid(), loaded.snapshot());
    }

    private List<Column> loadColumns(long snapshotId) {
        return jdbc.sql("""
                        select kolon_referansi, uretici_tipi as uretici_tip_kodu,
                               kanonik_tip as kanonik_tip_kodu,
                               sira_no, hassasiyet, olcek, uzunluk,
                               zaman_hassasiyeti, null_olabilir,
                               varsayilan_ifade, ad
                          from akis.kolon_goruntusu
                         where sema_goruntusu_id = :snapshotId
                         order by sira_no, kolon_referansi
                        """)
                .param("snapshotId", snapshotId)
                .query((rs, rowNum) -> new Column(
                        rs.getString("kolon_referansi"),
                        rs.getString("uretici_tip_kodu"),
                        rs.getString("kanonik_tip_kodu"), rs.getInt("sira_no"),
                        nullableInteger(rs, "hassasiyet"), nullableInteger(rs, "olcek"),
                        nullableLong(rs, "uzunluk"),
                        nullableInteger(rs, "zaman_hassasiyeti"),
                        rs.getBoolean("null_olabilir"),
                        rs.getString("varsayilan_ifade"), rs.getString("ad")))
                .list();
    }

    private List<Constraint> loadConstraints(long snapshotId) {
        return jdbc.sql("""
                        select kg.id, kg.dis_referans,
                               case kg.tur when 'BIRINCIL_ANAHTAR' then 'PK' when 'BENZERSIZ' then 'UK'
                                 when 'YABANCI_ANAHTAR' then 'FK' when 'KONTROL' then 'CHECK' end as tur_kodu,
                               kg.etkin_mi as etkin, kg.ayrinti_sema_surumu as ayrinti_surumu, kg.ayrinti, kg.ad
                          from akis.kisit_goruntusu kg
                         where kg.sema_goruntusu_id = :snapshotId
                         order by kg.dis_referans
                        """)
                .param("snapshotId", snapshotId)
                .query((rs, rowNum) -> new Constraint(
                        rs.getString("dis_referans"), rs.getString("tur_kodu"),
                        rs.getBoolean("etkin"), rs.getInt("ayrinti_surumu"),
                        json(rs.getString("ayrinti")), rs.getString("ad"),
                        loadConstraintColumns(rs.getLong("id"))))
                .list();
    }

    private List<String> loadConstraintColumns(long constraintId) {
        return jdbc.sql("""
                        select kgo.kolon_referansi
                          from akis.kisit_kolonu kk
                          join akis.kolon_goruntusu kgo
                            on kgo.proje_id = kk.proje_id
                           and kgo.id = kk.kolon_goruntusu_id
                         where kk.kisit_goruntusu_id = :constraintId
                         order by kk.sira_no
                        """)
                .param("constraintId", constraintId)
                .query(String.class)
                .list();
    }

    private SnapshotHeader mapHeader(ResultSet rs, int rowNum) throws SQLException {
        return new SnapshotHeader(
                rs.getObject("project_uuid", UUID.class),
                rs.getObject("publication_uuid", UUID.class),
                rs.getLong("snapshot_id"), rs.getObject("snapshot_uuid", UUID.class),
                rs.getString("parmak_izi"), rs.getString("motor_surumu"),
                rs.getInt("ozellik_surumu"), json(rs.getString("ozellik")));
    }

    private JsonNode json(String value) {
        try {
            JsonNode node = objectMapper.readTree(value);
            if (node == null) {
                throw failure(Failure.STORED_BODY_INVALID);
            }
            return node;
        }
        catch (JacksonException exception) {
            throw failure(Failure.STORED_BODY_INVALID);
        }
    }

    private void validatePlan(MappingExecutionContract plan) {
        if (plan == null || plan.definitionUuid() == null
                || plan.definitionVersionUuid() == null
                || !hash(plan.releaseHash())) {
            throw failure(Failure.INVALID_CONTRACT);
        }
    }

    private void validateBinding(DatasetBinding binding, DatasetRole role) {
        if (binding == null || binding.role() != role
                || binding.databaseType() != PilotRuntimePlan.DatabaseType.ORACLE
                || binding.dataObjectType() != PilotRuntimePlan.DataObjectType.TABLE
                || blank(binding.datasetId()) || binding.definitionDataObjectUuid() == null
                || binding.dataObjectUuid() == null
                || binding.environmentSchemaBindingUuid() == null
                || binding.physicalSchemaUuid() == null
                || binding.connectionVersionUuid() == null
                || binding.schemaSnapshotUuid() == null
                || binding.bindingVersion() < 1 || blank(binding.physicalIdentity())
                || blank(binding.owner()) || blank(binding.objectName())
                || !hash(binding.schemaSnapshotFingerprint())) {
            throw failure(Failure.INVALID_CONTRACT);
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private boolean hash(String value) {
        return value != null && HASH.matcher(value).matches();
    }

    private boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null || expected.length() != actual.length()) {
            return false;
        }
        int difference = 0;
        for (int index = 0; index < expected.length(); index++) {
            difference |= expected.charAt(index) ^ actual.charAt(index);
        }
        return difference == 0;
    }

    private Integer nullableInteger(ResultSet rs, String field) throws SQLException {
        int value = rs.getInt(field);
        return rs.wasNull() ? null : value;
    }

    private Long nullableLong(ResultSet rs, String field) throws SQLException {
        long value = rs.getLong(field);
        return rs.wasNull() ? null : value;
    }

    private PinnedSchemaSnapshotException failure(Failure failure) {
        return new PinnedSchemaSnapshotException(failure);
    }

    private record SnapshotHeader(
            UUID projectUuid,
            UUID publicationUuid,
            long snapshotId,
            UUID snapshotUuid,
            String fingerprint,
            String engineVersion,
            int propertyVersion,
            JsonNode properties) {
    }

    private record LoadedSnapshot(
            UUID projectUuid, UUID publicationUuid, PinnedSnapshot snapshot) {
    }

    record PinnedProcedureSource(
            UUID projectUuid, UUID publicationUuid, PinnedSnapshot snapshot) {
    }

    record PinnedProcedureTarget(
            UUID projectUuid, UUID publicationUuid, PinnedSnapshot snapshot) {
    }
}
