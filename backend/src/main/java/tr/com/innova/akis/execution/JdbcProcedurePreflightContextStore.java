package tr.com.innova.akis.execution;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.execution.ProcedurePreflightContextPort.Context;
import tr.com.innova.akis.execution.ProcedurePreflightContextPort.ConnectionEvidence;

/** Loads the immutable publication inputs used by a source-only preflight. */
@Repository
public class JdbcProcedurePreflightContextStore
        implements ProcedurePreflightContextPort {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcProcedurePreflightContextStore(
            JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Context> find(UUID projectUuid, UUID publicationUuid) {
        return jdbc.sql("""
                        select p.uuid as project_uuid,
                               y.uuid as publication_uuid,
                               y.durum as durum_kodu,
                               y.fiziksel_manifesto ->> 'releaseHash' as release_hash,
                               s.plan_ozeti,
                               s.plan,
                               y.fiziksel_manifesto
                          from akis.yayin y
                          join akis.proje p on p.id = y.proje_id
                          join akis.senaryo s on s.id = y.senaryo_id
                         where p.uuid = :projectUuid
                           and y.uuid = :publicationUuid
                           and y.durum in ('AKTIF', 'ONAY_BEKLIYOR')
                           and y.fiziksel_manifesto ->> 'runtimeCapability'
                               = 'ORACLE_PROCEDURE_V1'
                        """)
                .param("projectUuid", projectUuid)
                .param("publicationUuid", publicationUuid)
                .query((rs, rowNum) -> new Context(
                        rs.getObject("project_uuid", UUID.class),
                        rs.getObject("publication_uuid", UUID.class),
                        rs.getString("durum_kodu"),
                        rs.getString("release_hash"),
                        rs.getString("plan_ozeti"),
                        json(rs.getString("plan")),
                        json(rs.getString("fiziksel_manifesto"))))
                .optional();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ConnectionEvidence> findConnectionEvidence(
            UUID projectUuid, UUID connectionVersionUuid) {
        return jdbc.sql("""
                        select t.hedef_kimlik_surumu,
                               t.hedef_parmak_izi
                          from akis.baglanti b
                          join akis.baglanti_testi t on t.baglanti_id = b.id
                         where b.uuid = :connectionUuid
                           and b.durum = 'ETKIN'
                           and t.sonuc = 'BASARILI'
                           and t.hedef_kimlik_surumu = 1
                           and t.hedef_parmak_izi ~ '^[0-9a-f]{64}$'
                         order by t.deneme_no desc
                         limit 1
                        """)
                .param("connectionUuid", connectionVersionUuid)
                .query((rs, rowNum) -> new ConnectionEvidence(
                        rs.getInt("hedef_kimlik_surumu"),
                        rs.getString("hedef_parmak_izi")))
                .optional();
    }

    private JsonNode json(String value) {
        try {
            JsonNode node = objectMapper.readTree(value);
            if (node == null || !node.isObject()) {
                throw new IllegalStateException("Stored Procedure JSON is invalid.");
            }
            return node;
        }
        catch (JacksonException exception) {
            throw new IllegalStateException("Stored Procedure JSON is invalid.");
        }
    }
}
