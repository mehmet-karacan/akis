package tr.com.innova.akis.scenario;

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

import tr.com.innova.akis.metadata.DefinitionType;
import tr.com.innova.akis.scenario.ScenarioModels.CompiledPlan;
import tr.com.innova.akis.scenario.ScenarioModels.ScenarioRow;
import tr.com.innova.akis.scenario.ScenarioModels.SourceVersion;

@Repository
public class JdbcScenarioStore implements ScenarioStore {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcScenarioStore(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<SourceVersion> findSource(
            UUID projectUuid, UUID definitionUuid, UUID definitionVersionUuid) {
        return jdbc.sql("""
                        select s.id, p.id as project_id, p.uuid as project_uuid,
                               t.uuid as definition_uuid, s.uuid as version_uuid,
                               t.tur, s.surum_no, s.sema_surumu,
                               s.icerik_ozeti, s.icerik
                          from akis.tanim_surumu s
                          join akis.tanim t on t.id = s.tanim_id
                          join akis.proje p on p.id = t.proje_id
                         where p.uuid = :projectUuid
                           and t.uuid = :definitionUuid
                           and s.uuid = :definitionVersionUuid
                        """)
                .param("projectUuid", projectUuid)
                .param("definitionUuid", definitionUuid)
                .param("definitionVersionUuid", definitionVersionUuid)
                .query(this::mapSource)
                .optional();
    }

    @Override
    public void lockSource(long sourceVersionId) {
        jdbc.sql("select id from akis.tanim_surumu where id = :id for update")
                .param("id", sourceVersionId)
                .query(Long.class)
                .single();
    }

    @Override
    public Optional<ScenarioRow> findByPlanHash(long sourceVersionId, String planHash) {
        return jdbc.sql(scenarioSelect() + """
                         where s.tanim_surumu_id = :sourceVersionId
                           and s.plan_ozeti = :planHash
                        """)
                .param("sourceVersionId", sourceVersionId)
                .param("planHash", planHash)
                .query(this::mapScenario)
                .optional();
    }

    @Override
    public ScenarioRow create(
            SourceVersion source, CompiledPlan compiledPlan, UUID scenarioUuid) {
        long validationId = jdbc.sql("""
                        insert into akis.dogrulama(
                            proje_id, tanim_surumu_id, icerik_ozeti, sonuc,
                            hata_sayisi, uyari_sayisi, sonuc_sema_surumu, sonuc_ayrintisi)
                        values (:projectId, :sourceVersionId, :contentHash, 'GECTI', 0, 0, 1,
                                cast(:result as jsonb))
                        returning id
                        """)
                .param("sourceVersionId", source.id())
                .param("projectId", source.projectId())
                .param("contentHash", source.contentHash())
                .param("result", compiledPlan.validationResult().toString())
                .query(Long.class)
                .single();
        Integer nextVersion = jdbc.sql("""
                        select coalesce(max(surum_no), 0) + 1
                          from akis.senaryo
                         where tanim_surumu_id = :sourceVersionId
                        """)
                .param("sourceVersionId", source.id())
                .query(Integer.class)
                .single();
        jdbc.sql("""
                        insert into akis.senaryo(
                            proje_id, tanim_surumu_id, dogrulama_id, surum_no, plan_sema_surumu,
                            plan_ozeti, plan, parametre_semasi, uuid)
                        values (:projectId, :sourceVersionId, :validationId, :scenarioVersion,
                                :planVersion, :planHash, cast(:plan as jsonb),
                                cast(:parameterSchema as jsonb), :uuid)
                        """)
                .param("sourceVersionId", source.id())
                .param("projectId", source.projectId())
                .param("validationId", validationId)
                .param("scenarioVersion", nextVersion)
                .param("planVersion", compiledPlan.planVersion())
                .param("planHash", compiledPlan.planHash())
                .param("plan", compiledPlan.plan().toString())
                .param("parameterSchema", compiledPlan.parameterSchema().toString())
                .param("uuid", scenarioUuid)
                .update();
        return findByPlanHash(source.id(), compiledPlan.planHash()).orElseThrow();
    }

    @Override
    public Optional<ScenarioRow> find(
            UUID projectUuid,
            UUID definitionUuid,
            UUID definitionVersionUuid,
            UUID scenarioUuid) {
        return jdbc.sql(scenarioSelect() + """
                         where p.uuid = :projectUuid
                           and t.uuid = :definitionUuid
                           and v.uuid = :definitionVersionUuid
                           and s.uuid = :scenarioUuid
                        """)
                .param("projectUuid", projectUuid)
                .param("definitionUuid", definitionUuid)
                .param("definitionVersionUuid", definitionVersionUuid)
                .param("scenarioUuid", scenarioUuid)
                .query(this::mapScenario)
                .optional();
    }

    @Override
    public List<ScenarioRow> list(
            UUID projectUuid, UUID definitionUuid, UUID definitionVersionUuid) {
        return jdbc.sql(scenarioSelect() + """
                         where p.uuid = :projectUuid
                           and t.uuid = :definitionUuid
                           and v.uuid = :definitionVersionUuid
                         order by s.surum_no desc
                        """)
                .param("projectUuid", projectUuid)
                .param("definitionUuid", definitionUuid)
                .param("definitionVersionUuid", definitionVersionUuid)
                .query(this::mapScenario)
                .list();
    }

    private String scenarioSelect() {
        return """
                select s.id, s.uuid, t.uuid as definition_uuid,
                       v.uuid as definition_version_uuid, t.tur,
                       s.surum_no, s.plan_sema_surumu as plan_surumu, s.plan_ozeti, s.plan,
                       s.parametre_semasi, s.olusturulma_zamani
                  from akis.senaryo s
                  join akis.tanim_surumu v on v.id = s.tanim_surumu_id
                  join akis.tanim t on t.id = v.tanim_id
                  join akis.proje p on p.id = t.proje_id
                """;
    }

    private SourceVersion mapSource(ResultSet rs, int rowNum) throws SQLException {
        return new SourceVersion(
                rs.getLong("id"), rs.getLong("project_id"),
                rs.getObject("project_uuid", UUID.class),
                rs.getObject("definition_uuid", UUID.class),
                rs.getObject("version_uuid", UUID.class),
                apiDefinitionType(rs.getString("tur")),
                rs.getInt("surum_no"), rs.getInt("sema_surumu"),
                rs.getString("icerik_ozeti"), json(rs.getString("icerik")));
    }

    private ScenarioRow mapScenario(ResultSet rs, int rowNum) throws SQLException {
        return new ScenarioRow(
                rs.getLong("id"), rs.getObject("uuid", UUID.class),
                rs.getObject("definition_uuid", UUID.class),
                rs.getObject("definition_version_uuid", UUID.class),
                apiDefinitionType(rs.getString("tur")),
                rs.getInt("surum_no"), rs.getInt("plan_surumu"),
                rs.getString("plan_ozeti"), json(rs.getString("plan")),
                json(rs.getString("parametre_semasi")),
                rs.getObject("olusturulma_zamani", OffsetDateTime.class));
    }

    private JsonNode json(String value) {
        try {
            return objectMapper.readTree(value);
        }
        catch (JacksonException exception) {
            throw new IllegalStateException("Stored Scenario JSON could not be read.", exception);
        }
    }

    private DefinitionType apiDefinitionType(String type) {
        return switch (type) {
            case "MAPPING" -> DefinitionType.MAPPING;
            case "YENIDEN_KULLANILABILIR_MAPPING" -> DefinitionType.REUSABLE_MAPPING;
            case "PAKET" -> DefinitionType.PACKAGE;
            case "PROSEDUR" -> DefinitionType.PROCEDURE;
            case "DEGISKEN" -> DefinitionType.VARIABLE;
            case "SEQUENCE" -> DefinitionType.SEQUENCE;
            case "KNOWLEDGE_MODULE" -> DefinitionType.KNOWLEDGE_MODULE;
            case "LOAD_PLAN" -> DefinitionType.LOAD_PLAN;
            default -> throw new IllegalStateException("Bilinmeyen tanım türü: " + type);
        };
    }
}
