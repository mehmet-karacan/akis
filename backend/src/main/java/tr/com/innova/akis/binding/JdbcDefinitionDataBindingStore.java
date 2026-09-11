package tr.com.innova.akis.binding;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.binding.BindingModels.BindingRow;
import tr.com.innova.akis.binding.BindingModels.CreateBinding;
import tr.com.innova.akis.binding.BindingModels.DataObjectRef;
import tr.com.innova.akis.binding.BindingModels.DefinitionVersionRef;
import tr.com.innova.akis.binding.BindingModels.ProjectRef;
import tr.com.innova.akis.binding.BindingModels.SchemaSnapshotRef;
import tr.com.innova.akis.metadata.DefinitionType;

@Repository
public class JdbcDefinitionDataBindingStore implements DefinitionDataBindingStore {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcDefinitionDataBindingStore(JdbcClient jdbc, ObjectMapper objectMapper) {
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
    public Optional<DefinitionVersionRef> findDefinitionVersion(
            long projectId, UUID definitionUuid, UUID definitionVersionUuid) {
        return jdbc.sql("""
                        select ts.id, t.uuid as definition_uuid,
                               ts.uuid as definition_version_uuid, t.tur_kodu,
                               ts.icerik
                          from entegrasyon.tanim t
                          join entegrasyon.tanim_surumu ts on ts.tanim_id = t.id
                         where t.proje_id = :projectId
                           and t.uuid = :definitionUuid
                           and ts.uuid = :definitionVersionUuid
                        """)
                .param("projectId", projectId)
                .param("definitionUuid", definitionUuid)
                .param("definitionVersionUuid", definitionVersionUuid)
                .query((rs, rowNum) -> new DefinitionVersionRef(
                        rs.getLong("id"),
                        rs.getObject("definition_uuid", UUID.class),
                        rs.getObject("definition_version_uuid", UUID.class),
                        DefinitionType.valueOf(rs.getString("tur_kodu")),
                        readJson(rs.getString("icerik"))))
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
    public Optional<SchemaSnapshotRef> findSchemaSnapshot(
            long projectId, UUID schemaSnapshotUuid) {
        return jdbc.sql("""
                        select id, uuid, veri_nesnesi_id
                          from entegrasyon.sema_goruntusu
                         where proje_id = :projectId and uuid = :uuid
                        """)
                .param("projectId", projectId)
                .param("uuid", schemaSnapshotUuid)
                .query((rs, rowNum) -> new SchemaSnapshotRef(
                        rs.getLong("id"), rs.getObject("uuid", UUID.class),
                        rs.getLong("veri_nesnesi_id")))
                .optional();
    }

    @Override
    public boolean nodeExists(long definitionVersionId, String nodeCode) {
        return jdbc.sql("""
                        select exists(
                            select 1
                              from entegrasyon.tanim_veri_nesnesi
                             where tanim_surumu_id = :definitionVersionId
                               and dugum_kodu = :nodeCode)
                        """)
                .param("definitionVersionId", definitionVersionId)
                .param("nodeCode", nodeCode)
                .query(Boolean.class)
                .single();
    }

    @Override
    public BindingRow create(CreateBinding binding) {
        return jdbc.sql("""
                        insert into entegrasyon.tanim_veri_nesnesi(
                            proje_id, tanim_surumu_id, veri_nesnesi_id,
                            sema_goruntusu_id, dugum_kodu, rol_kodu, uuid)
                        values (:projectId, :definitionVersionId, :dataObjectId,
                                :schemaSnapshotId, :nodeCode, :role, :uuid)
                        returning olusturulma_zamani
                        """)
                .param("projectId", binding.projectId())
                .param("definitionVersionId", binding.definitionVersionId())
                .param("dataObjectId", binding.dataObjectId())
                .param("schemaSnapshotId", binding.schemaSnapshotId())
                .param("nodeCode", binding.nodeCode())
                .param("role", binding.role().name())
                .param("uuid", binding.uuid())
                .query((rs, rowNum) -> new BindingRow(
                        binding.uuid(), binding.definitionUuid(),
                        binding.definitionVersionUuid(), binding.nodeCode(), binding.role(),
                        binding.dataObjectUuid(), binding.schemaSnapshotUuid(),
                        rs.getObject("olusturulma_zamani", OffsetDateTime.class)))
                .single();
    }

    @Override
    public Optional<BindingRow> find(
            long projectId, long definitionVersionId, UUID bindingUuid) {
        return jdbc.sql(bindingSelect() + """
                         where tvn.proje_id = :projectId
                           and tvn.tanim_surumu_id = :definitionVersionId
                           and tvn.uuid = :bindingUuid
                        """)
                .param("projectId", projectId)
                .param("definitionVersionId", definitionVersionId)
                .param("bindingUuid", bindingUuid)
                .query(this::map)
                .optional();
    }

    @Override
    public List<BindingRow> list(long projectId, long definitionVersionId) {
        return jdbc.sql(bindingSelect() + """
                         where tvn.proje_id = :projectId
                           and tvn.tanim_surumu_id = :definitionVersionId
                         order by tvn.dugum_kodu
                        """)
                .param("projectId", projectId)
                .param("definitionVersionId", definitionVersionId)
                .query(this::map)
                .list();
    }

    private String bindingSelect() {
        return """
                select tvn.uuid, t.uuid as definition_uuid,
                       ts.uuid as definition_version_uuid, tvn.dugum_kodu,
                       tvn.rol_kodu, vn.uuid as data_object_uuid,
                       sg.uuid as schema_snapshot_uuid, tvn.olusturulma_zamani
                  from entegrasyon.tanim_veri_nesnesi tvn
                  join entegrasyon.tanim_surumu ts on ts.id = tvn.tanim_surumu_id
                  join entegrasyon.tanim t on t.id = ts.tanim_id
                  join entegrasyon.veri_nesnesi vn on vn.id = tvn.veri_nesnesi_id
                  join entegrasyon.sema_goruntusu sg on sg.id = tvn.sema_goruntusu_id
                """;
    }

    private BindingRow map(ResultSet rs, int rowNum) throws SQLException {
        return new BindingRow(
                rs.getObject("uuid", UUID.class),
                rs.getObject("definition_uuid", UUID.class),
                rs.getObject("definition_version_uuid", UUID.class),
                rs.getString("dugum_kodu"), BindingRole.valueOf(rs.getString("rol_kodu")),
                rs.getObject("data_object_uuid", UUID.class),
                rs.getObject("schema_snapshot_uuid", UUID.class),
                rs.getObject("olusturulma_zamani", OffsetDateTime.class));
    }

    private JsonNode readJson(String value) {
        try {
            return objectMapper.readTree(value);
        }
        catch (Exception exception) {
            throw new IllegalStateException("Stored definition content is invalid JSON.", exception);
        }
    }
}
