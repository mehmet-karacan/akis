package tr.com.innova.akis.discovery;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.discovery.SchemaSnapshotModels.ColumnInput;
import tr.com.innova.akis.discovery.SchemaSnapshotModels.ColumnRow;
import tr.com.innova.akis.discovery.SchemaSnapshotModels.ConnectionVersionRef;
import tr.com.innova.akis.discovery.SchemaSnapshotModels.ConstraintInput;
import tr.com.innova.akis.discovery.SchemaSnapshotModels.ConstraintRow;
import tr.com.innova.akis.discovery.SchemaSnapshotModels.CreateSnapshot;
import tr.com.innova.akis.discovery.SchemaSnapshotModels.DataObjectRef;
import tr.com.innova.akis.discovery.SchemaSnapshotModels.PhysicalSchemaRef;
import tr.com.innova.akis.discovery.SchemaSnapshotModels.ProjectRef;
import tr.com.innova.akis.discovery.SchemaSnapshotModels.SnapshotRow;

@Repository
public class JdbcSchemaSnapshotStore implements SchemaSnapshotStore {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcSchemaSnapshotStore(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<ProjectRef> findProject(UUID projectUuid) {
        return jdbc.sql("select id from entegrasyon.proje where uuid = :uuid")
                .param("uuid", projectUuid)
                .query((rs, rowNum) -> new ProjectRef(rs.getLong("id")))
                .optional();
    }

    @Override
    public Optional<DataObjectRef> findDataObject(long projectId, UUID dataObjectUuid) {
        return jdbc.sql("""
                        select id, uuid
                          from entegrasyon.veri_nesnesi
                         where proje_id = :projectId and uuid = :uuid
                        """)
                .param("projectId", projectId)
                .param("uuid", dataObjectUuid)
                .query((rs, rowNum) -> new DataObjectRef(
                        rs.getLong("id"), rs.getObject("uuid", UUID.class)))
                .optional();
    }

    @Override
    public Optional<PhysicalSchemaRef> findPhysicalSchema(
            long projectId, UUID physicalSchemaUuid) {
        return jdbc.sql("""
                        select id, uuid, baglanti_id
                          from entegrasyon.fiziksel_sema
                         where proje_id = :projectId and uuid = :uuid
                        """)
                .param("projectId", projectId)
                .param("uuid", physicalSchemaUuid)
                .query((rs, rowNum) -> new PhysicalSchemaRef(
                        rs.getLong("id"), rs.getObject("uuid", UUID.class),
                        rs.getLong("baglanti_id")))
                .optional();
    }

    @Override
    public Optional<ConnectionVersionRef> findConnectionVersion(
            long projectId, UUID connectionVersionUuid) {
        return jdbc.sql("""
                        select v.id, v.uuid, v.baglanti_id
                          from entegrasyon.baglanti_surumu v
                          join entegrasyon.baglanti b on b.id = v.baglanti_id
                         where b.proje_id = :projectId and v.uuid = :uuid
                        """)
                .param("projectId", projectId)
                .param("uuid", connectionVersionUuid)
                .query((rs, rowNum) -> new ConnectionVersionRef(
                        rs.getLong("id"), rs.getObject("uuid", UUID.class),
                        rs.getLong("baglanti_id")))
                .optional();
    }

    @Override
    public SnapshotRow create(CreateSnapshot snapshot) {
        long snapshotId = jdbc.sql("""
                        insert into entegrasyon.sema_goruntusu(
                            proje_id, veri_nesnesi_id, fiziksel_sema_id,
                            baglanti_surumu_id, uuid, parmak_izi, motor_surumu,
                            kesif_zamani, ozellik_surumu, ozellik)
                        values (:projectId, :dataObjectId, :physicalSchemaId,
                                :connectionVersionId, :uuid, :fingerprint, :engineVersion,
                                :discoveredAt, :propertyVersion, cast(:properties as jsonb))
                        returning id
                        """)
                .param("projectId", snapshot.projectId())
                .param("dataObjectId", snapshot.dataObjectId())
                .param("physicalSchemaId", snapshot.physicalSchemaId())
                .param("connectionVersionId", snapshot.connectionVersionId())
                .param("uuid", snapshot.uuid())
                .param("fingerprint", snapshot.fingerprint())
                .param("engineVersion", snapshot.engineVersion())
                .param("discoveredAt", snapshot.discoveredAt())
                .param("propertyVersion", snapshot.propertyVersion())
                .param("properties", snapshot.properties().toString())
                .query(Long.class)
                .single();

        Map<String, Long> columnIds = new LinkedHashMap<>();
        for (ColumnInput column : snapshot.columns()) {
            long columnId = insertColumn(snapshot.projectId(), snapshotId, column);
            columnIds.put(column.reference(), columnId);
        }
        for (ConstraintInput constraint : snapshot.constraints()) {
            long constraintId = insertConstraint(snapshot.projectId(), snapshotId, constraint);
            for (int index = 0; index < constraint.columnReferences().size(); index++) {
                insertConstraintColumn(
                        snapshot.projectId(), constraintId,
                        columnIds.get(constraint.columnReferences().get(index)), index + 1);
            }
        }
        return find(snapshot.projectId(), snapshot.uuid()).orElseThrow();
    }

    private long insertColumn(long projectId, long snapshotId, ColumnInput column) {
        return jdbc.sql("""
                        insert into entegrasyon.kolon_goruntusu(
                            proje_id, sema_goruntusu_id, uuid, kolon_referansi,
                            uretici_tip_kodu, kanonik_tip_kodu, sira_no, hassasiyet,
                            olcek, uzunluk, zaman_hassasiyeti, null_olabilir,
                            varsayilan_ifade, ad)
                        values (:projectId, :snapshotId, :uuid, :reference,
                                :producerType, :canonicalType, :ordinal, :precision,
                                :scale, :length, :timePrecision, :nullable,
                                :defaultExpression, :name)
                        returning id
                        """)
                .param("projectId", projectId)
                .param("snapshotId", snapshotId)
                .param("uuid", UUID.randomUUID())
                .param("reference", column.reference())
                .param("producerType", column.producerType())
                .param("canonicalType", column.canonicalType())
                .param("ordinal", column.ordinal())
                .param("precision", column.precision(), Types.INTEGER)
                .param("scale", column.scale(), Types.INTEGER)
                .param("length", column.length(), Types.BIGINT)
                .param("timePrecision", column.timePrecision(), Types.INTEGER)
                .param("nullable", column.nullable())
                .param("defaultExpression", column.defaultExpression(), Types.VARCHAR)
                .param("name", column.name())
                .query(Long.class)
                .single();
    }

    private long insertConstraint(
            long projectId, long snapshotId, ConstraintInput constraint) {
        return jdbc.sql("""
                        insert into entegrasyon.kisit_goruntusu(
                            proje_id, sema_goruntusu_id, uuid, dis_referans,
                            tur_kodu, etkin, ayrinti_surumu, ayrinti, ad)
                        values (:projectId, :snapshotId, :uuid, :externalReference,
                                :type, :enabled, :detailVersion, cast(:details as jsonb), :name)
                        returning id
                        """)
                .param("projectId", projectId)
                .param("snapshotId", snapshotId)
                .param("uuid", UUID.randomUUID())
                .param("externalReference", constraint.externalReference())
                .param("type", constraint.type())
                .param("enabled", constraint.enabled())
                .param("detailVersion", constraint.detailVersion())
                .param("details", constraint.details().toString())
                .param("name", constraint.name())
                .query(Long.class)
                .single();
    }

    private void insertConstraintColumn(
            long projectId, long constraintId, long columnId, int ordinal) {
        jdbc.sql("""
                        insert into entegrasyon.kisit_kolonu(
                            proje_id, kisit_goruntusu_id, kolon_goruntusu_id,
                            sira_no, uuid)
                        values (:projectId, :constraintId, :columnId, :ordinal, :uuid)
                        """)
                .param("projectId", projectId)
                .param("constraintId", constraintId)
                .param("columnId", columnId)
                .param("ordinal", ordinal)
                .param("uuid", UUID.randomUUID())
                .update();
    }

    @Override
    public Optional<SnapshotRow> find(long projectId, UUID snapshotUuid) {
        return jdbc.sql(snapshotSelect()
                        + " where s.proje_id = :projectId and s.uuid = :uuid")
                .param("projectId", projectId)
                .param("uuid", snapshotUuid)
                .query(this::mapHeader)
                .optional()
                .map(this::loadChildren);
    }

    @Override
    public List<SnapshotRow> list(long projectId, UUID dataObjectUuid) {
        return jdbc.sql(snapshotSelect() + """
                         where s.proje_id = :projectId and d.uuid = :dataObjectUuid
                         order by s.kesif_zamani desc, s.id desc
                        """)
                .param("projectId", projectId)
                .param("dataObjectUuid", dataObjectUuid)
                .query(this::mapHeader)
                .list()
                .stream()
                .map(this::loadChildren)
                .toList();
    }

    private SnapshotRow loadChildren(SnapshotRow snapshot) {
        List<ColumnRow> columns = jdbc.sql("""
                        select uuid, kolon_referansi, uretici_tip_kodu,
                               kanonik_tip_kodu, sira_no, hassasiyet, olcek,
                               uzunluk, zaman_hassasiyeti, null_olabilir,
                               varsayilan_ifade, ad
                          from entegrasyon.kolon_goruntusu
                         where sema_goruntusu_id = :snapshotId
                         order by sira_no
                        """)
                .param("snapshotId", snapshot.id())
                .query(this::mapColumn)
                .list();
        List<ConstraintRow> constraints = jdbc.sql("""
                        select id, uuid, dis_referans, tur_kodu, etkin,
                               ayrinti_surumu, ayrinti, ad
                          from entegrasyon.kisit_goruntusu
                         where sema_goruntusu_id = :snapshotId
                         order by dis_referans
                        """)
                .param("snapshotId", snapshot.id())
                .query((rs, rowNum) -> mapConstraint(rs))
                .list();
        return new SnapshotRow(
                snapshot.id(), snapshot.uuid(), snapshot.dataObjectUuid(),
                snapshot.physicalSchemaUuid(), snapshot.connectionVersionUuid(),
                snapshot.fingerprint(), snapshot.engineVersion(), snapshot.discoveredAt(),
                snapshot.propertyVersion(), snapshot.properties(), snapshot.createdAt(),
                columns, constraints);
    }

    private String snapshotSelect() {
        return """
                select s.id, s.uuid, d.uuid as data_object_uuid,
                       p.uuid as physical_schema_uuid,
                       v.uuid as connection_version_uuid, s.parmak_izi,
                       s.motor_surumu, s.kesif_zamani, s.ozellik_surumu,
                       s.ozellik, s.olusturulma_zamani
                  from entegrasyon.sema_goruntusu s
                  join entegrasyon.veri_nesnesi d on d.id = s.veri_nesnesi_id
                  join entegrasyon.fiziksel_sema p on p.id = s.fiziksel_sema_id
                  join entegrasyon.baglanti_surumu v on v.id = s.baglanti_surumu_id
                """;
    }

    private SnapshotRow mapHeader(ResultSet rs, int rowNum) throws SQLException {
        return new SnapshotRow(
                rs.getLong("id"), rs.getObject("uuid", UUID.class),
                rs.getObject("data_object_uuid", UUID.class),
                rs.getObject("physical_schema_uuid", UUID.class),
                rs.getObject("connection_version_uuid", UUID.class),
                rs.getString("parmak_izi"), rs.getString("motor_surumu"),
                rs.getObject("kesif_zamani", OffsetDateTime.class),
                rs.getInt("ozellik_surumu"), json(rs.getString("ozellik")),
                rs.getObject("olusturulma_zamani", OffsetDateTime.class), List.of(), List.of());
    }

    private ColumnRow mapColumn(ResultSet rs, int rowNum) throws SQLException {
        return new ColumnRow(
                rs.getObject("uuid", UUID.class), rs.getString("kolon_referansi"),
                rs.getString("uretici_tip_kodu"), rs.getString("kanonik_tip_kodu"),
                rs.getInt("sira_no"), rs.getObject("hassasiyet", Integer.class),
                rs.getObject("olcek", Integer.class), rs.getObject("uzunluk", Long.class),
                rs.getObject("zaman_hassasiyeti", Integer.class),
                rs.getBoolean("null_olabilir"), rs.getString("varsayilan_ifade"),
                rs.getString("ad"));
    }

    private ConstraintRow mapConstraint(ResultSet rs) throws SQLException {
        long constraintId = rs.getLong("id");
        List<String> columnReferences = jdbc.sql("""
                        select c.kolon_referansi
                          from entegrasyon.kisit_kolonu k
                          join entegrasyon.kolon_goruntusu c
                            on c.id = k.kolon_goruntusu_id
                         where k.kisit_goruntusu_id = :constraintId
                         order by k.sira_no
                        """)
                .param("constraintId", constraintId)
                .query(String.class)
                .list();
        return new ConstraintRow(
                rs.getObject("uuid", UUID.class), rs.getString("dis_referans"),
                rs.getString("tur_kodu"), rs.getBoolean("etkin"),
                rs.getInt("ayrinti_surumu"), json(rs.getString("ayrinti")),
                rs.getString("ad"), columnReferences);
    }

    private JsonNode json(String value) {
        try {
            return objectMapper.readTree(value);
        }
        catch (JacksonException exception) {
            throw new IllegalStateException("Stored schema JSON could not be read.", exception);
        }
    }
}
