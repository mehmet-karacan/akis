package tr.com.innova.akis.execution;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.execution.PinnedExecutionContextPort.PinnedExecutionContext;

@Repository
public class JdbcPinnedExecutionContextStore implements PinnedExecutionContextPort {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcPinnedExecutionContextStore(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PinnedExecutionContext> find(UUID runUuid) {
        return jdbc.sql("""
                        select it.uuid as job_request_uuid,
                               c.uuid as run_uuid,
                               y.uuid as publication_uuid,
                               c.deneme_no,
                               c.yayin_ozeti,
                               c.plan_ozeti,
                               s.plan as scenario_plan,
                               y.fiziksel_manifesto
                          from entegrasyon.calistirma c
                          join entegrasyon.is_talebi it
                            on it.proje_id = c.proje_id and it.id = c.is_talebi_id
                          join entegrasyon.yayin y
                            on y.proje_id = it.proje_id and y.id = it.yayin_id
                          join entegrasyon.senaryo s on s.id = y.senaryo_id
                         where c.uuid = :runUuid
                           and c.yayin_ozeti = y.release_hash
                           and c.plan_ozeti = s.plan_ozeti
                        """)
                .param("runUuid", runUuid)
                .query((rs, rowNum) -> new PinnedExecutionContext(
                        rs.getObject("job_request_uuid", UUID.class),
                        rs.getObject("run_uuid", UUID.class),
                        rs.getObject("publication_uuid", UUID.class),
                        rs.getInt("deneme_no"),
                        rs.getString("yayin_ozeti"),
                        rs.getString("plan_ozeti"),
                        json(rs.getString("scenario_plan")),
                        json(rs.getString("fiziksel_manifesto"))))
                .optional();
    }

    private JsonNode json(String value) {
        try {
            return objectMapper.readTree(value);
        }
        catch (JacksonException exception) {
            throw new IllegalStateException(
                    "Stored pinned execution JSON could not be read.", exception);
        }
    }
}
