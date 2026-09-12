package tr.com.innova.akis.web;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;

@Repository
public class AuditRepository {

    private final JdbcClient jdbc;

    public AuditRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Long> findProjectId(UUID projectUuid) {
        return jdbc.sql("select id from akis.proje where uuid = :uuid")
                .param("uuid", projectUuid)
                .query(Long.class)
                .optional();
    }

    void append(
            Long projectId,
            UUID externalObjectUuid,
            String correlationId,
            String actorType,
            String action,
            String result,
            JsonNode detail) {
        jdbc.sql("""
                        insert into akis.denetim_olayi(
                            proje_id, dis_nesne_uuid, korelasyon_kodu,
                            aktor_turu, eylem_kodu, sonuc, olay_zamani, ayrinti)
                        values (:projectId, :externalObjectUuid, :correlationId,
                                :actorType, :action, :result, current_timestamp,
                                cast(:detail as jsonb))
                        """)
                .param("projectId", projectId, java.sql.Types.BIGINT)
                .param("externalObjectUuid", externalObjectUuid, java.sql.Types.OTHER)
                .param("correlationId", correlationId)
                .param("actorType", actorType)
                .param("action", action)
                .param("result", result)
                .param("detail", detail.toString())
                .update();
    }
}
