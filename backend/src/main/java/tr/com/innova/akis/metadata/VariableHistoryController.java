package tr.com.innova.akis.metadata;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;
import tr.com.innova.akis.security.AuthorizationService;
import static tr.com.innova.akis.security.PermissionCodes.DEFINITION_READ;

@RestController
@RequestMapping("/api/v1/projects/{projectUuid}/definitions/{definitionUuid}/value-history")
final class VariableHistoryController {
    private final JdbcClient jdbc;
    private final AuthorizationService authorization;
    VariableHistoryController(JdbcClient jdbc, AuthorizationService authorization) {
        this.jdbc = jdbc; this.authorization = authorization;
    }
    record ValueHistory(long id, UUID runUuid, String environment, String logicalSchema,
                        String dataType, String value, OffsetDateTime createdAt) { }

    @GetMapping
    List<ValueHistory> list(@PathVariable UUID projectUuid, @PathVariable UUID definitionUuid,
                           @RequestParam(defaultValue="9223372036854775807") long before) {
        authorization.requireProjectPermission(projectUuid, DEFINITION_READ);
        return jdbc.sql("""
            select h.*, o.ad as environment_name, ms.ad as logical_name
              from akis.degisken_deger_gecmisi h
              join akis.ortam o on o.uuid=h.ortam_uuid
              join akis.mantiksal_sema ms on ms.uuid=h.mantiksal_sema_uuid
             where h.proje_uuid=:project and h.tanim_uuid=:definition and h.id<:before
             order by h.id desc limit 50
            """).param("project", projectUuid).param("definition", definitionUuid).param("before", before)
            .query((rs,n) -> new ValueHistory(rs.getLong("id"), rs.getObject("calistirma_uuid",UUID.class),
                rs.getString("environment_name"),rs.getString("logical_name"),rs.getString("veri_turu"),
                rs.getString("deger"),rs.getObject("olusturulma_zamani",OffsetDateTime.class))).list();
    }
}
