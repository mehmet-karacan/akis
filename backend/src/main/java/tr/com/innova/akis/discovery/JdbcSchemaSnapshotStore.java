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
        return jdbc.sql("select id from akis.proje where uuid = :uuid")
                .param("uuid", projectUuid)
                .query((rs, rowNum) -> new ProjectRef(rs.getLong("id")))
                .optional();
    }

    @Override
    public Optional<DataObjectRef> findDataObject(long projectId, UUID dataObjectUuid) {
        return jdbc.sql("""
                        select id, uuid
                          from akis.veri_nesnesi
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
                          from akis.fiziksel_sema
                         where uuid = :uuid
                        """)
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
                        select b.id, b.uuid, b.id as baglanti_id
                          from akis.baglanti b
                         where b.uuid = :uuid
                        """)
                .param("uuid", connectionVersionUuid)
                .query((rs, rowNum) -> new ConnectionVersionRef(
                        rs.getLong("id"), rs.getObject("uuid", UUID.class),
                        rs.getLong("baglanti_id")))
                .optional();
    }

    /** Engine and driver identification captured at discovery time; never part of the fingerprint. */
    private tools.jackson.databind.node.ObjectNode discoveryEvidence(SchemaSnapshotModels.SnapshotProvenance provenance) {
        var evidence = objectMapper.createObjectNode();
        if (provenance.engineProduct() != null) evidence.put("engineProduct", provenance.engineProduct());
        if (provenance.engineVersion() != null) evidence.put("engineVersion", provenance.engineVersion());
        if (provenance.driverName() != null) evidence.put("driverName", provenance.driverName());
        if (provenance.driverVersion() != null) evidence.put("driverVersion", provenance.driverVersion());
        return evidence;
    }

    @Override
    public SnapshotRow create(CreateSnapshot snapshot) {
        Optional<Long> insertedSnapshotId = jdbc.sql("""
                        insert into akis.sema_goruntusu(
                            proje_id, veri_nesnesi_id, fiziksel_sema_id,
                            baglanti_id, uuid, parmak_izi, motor_surumu,
                            kesif_zamani, ozellik_sema_surumu, ozellik,
                            teknoloji_kodu, parmak_izi_surumu, kesif_kaniti)
                        select :projectId, :dataObjectId, :physicalSchemaId,
                               fs.baglanti_id, :uuid, :fingerprint, :engineVersion,
                               :discoveredAt, :propertyVersion, cast(:properties as jsonb),
                               :technology, 1, cast(:evidence as jsonb)
                          from akis.fiziksel_sema fs
                         where fs.id = :physicalSchemaId
                        on conflict (veri_nesnesi_id, fiziksel_sema_id, parmak_izi) do nothing
                        returning id
                        """)
                .param("projectId", snapshot.projectId())
                .param("dataObjectId", snapshot.dataObjectId())
                .param("physicalSchemaId", snapshot.physicalSchemaId())
                .param("uuid", snapshot.uuid())
                .param("fingerprint", snapshot.fingerprint())
                .param("engineVersion", snapshot.engineVersion())
                .param("discoveredAt", snapshot.discoveredAt())
                .param("propertyVersion", snapshot.propertyVersion())
                .param("properties", snapshot.properties().toString())
                .param("technology", snapshot.provenance().technology())
                .param("evidence", discoveryEvidence(snapshot.provenance()).toString())
                .query(Long.class)
                .optional();

        if (insertedSnapshotId.isEmpty()) {
            UUID existingUuid = jdbc.sql("""
                            select uuid
                              from akis.sema_goruntusu
                             where veri_nesnesi_id = :dataObjectId
                               and fiziksel_sema_id = :physicalSchemaId
                               and parmak_izi = :fingerprint
                            """)
                    .param("dataObjectId", snapshot.dataObjectId())
                    .param("physicalSchemaId", snapshot.physicalSchemaId())
                    .param("fingerprint", snapshot.fingerprint())
                    .query(UUID.class)
                    .single();
            return find(snapshot.projectId(), existingUuid).orElseThrow();
        }
        long snapshotId = insertedSnapshotId.orElseThrow();

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
                        insert into akis.kolon_goruntusu(
                            proje_id, sema_goruntusu_id, uuid, kolon_referansi,
                            uretici_tipi, kanonik_tip, sira_no, hassasiyet,
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
                        insert into akis.kisit_goruntusu(
                            proje_id, sema_goruntusu_id, uuid, dis_referans,
                            tur, etkin_mi, ayrinti_sema_surumu, ayrinti, ad)
                        values (:projectId, :snapshotId, :uuid, :externalReference,
                                :type, :enabled, :detailVersion, cast(:details as jsonb), :name)
                        returning id
                        """)
                .param("projectId", projectId)
                .param("snapshotId", snapshotId)
                .param("uuid", UUID.randomUUID())
                .param("externalReference", constraint.externalReference())
                .param("type", databaseConstraintType(constraint.type()))
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
                        insert into akis.kisit_kolonu(
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
                        select uuid, kolon_referansi, uretici_tipi,
                               kanonik_tip, sira_no, hassasiyet, olcek,
                               uzunluk, zaman_hassasiyeti, null_olabilir,
                               varsayilan_ifade, ad
                          from akis.kolon_goruntusu
                         where sema_goruntusu_id = :snapshotId
                         order by sira_no
                        """)
                .param("snapshotId", snapshot.id())
                .query(this::mapColumn)
                .list();
        List<ConstraintRow> constraints = jdbc.sql("""
                        select id, uuid, dis_referans,
                               case tur when 'BIRINCIL_ANAHTAR' then 'PK'
                                   when 'BENZERSIZ' then 'UK' when 'YABANCI_ANAHTAR' then 'FK'
                                   when 'KONTROL' then 'CHECK' end as tur_kodu,
                               etkin_mi as etkin, ayrinti_sema_surumu as ayrinti_surumu, ayrinti, ad
                          from akis.kisit_goruntusu
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
                       b.uuid as connection_version_uuid, s.parmak_izi,
                       s.motor_surumu, s.kesif_zamani, s.ozellik_sema_surumu as ozellik_surumu,
                       s.ozellik, s.olusturulma_zamani
                  from akis.sema_goruntusu s
                  join akis.veri_nesnesi d on d.id = s.veri_nesnesi_id
                  join akis.fiziksel_sema p on p.id = s.fiziksel_sema_id
                  join akis.baglanti b on b.id = s.baglanti_id
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
                rs.getString("uretici_tipi"), rs.getString("kanonik_tip"),
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
                          from akis.kisit_kolonu k
                          join akis.kolon_goruntusu c
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

    private String databaseConstraintType(String type) {
        return switch (type) {
            case "PK" -> "BIRINCIL_ANAHTAR";
            case "UK" -> "BENZERSIZ";
            case "FK" -> "YABANCI_ANAHTAR";
            case "CHECK" -> "KONTROL";
            default -> throw new IllegalArgumentException("Unsupported constraint type: " + type);
        };
    }
}
