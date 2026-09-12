package tr.com.innova.akis.oracle;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import tr.com.innova.akis.metadata.ApiException;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.ConnectionProbe;
import tr.com.innova.akis.security.AuthorizationService;
import static tr.com.innova.akis.security.PermissionCodes.DISCOVERY_WRITE;

/** Tests an unsaved Oracle definition without persisting metadata or evidence. */
@RestController
@RequestMapping("/api/v2/projects/{projectUuid}/connections/test")
final class OracleDraftConnectionTestController {

    private final OracleDiscoveryService service;
    private final AuthorizationService authorization;

    OracleDraftConnectionTestController(
            OracleDiscoveryService service,
            AuthorizationService authorization) {
        this.service = service;
        this.authorization = authorization;
    }

    @PostMapping
    DraftConnectionTestView test(
            @PathVariable UUID projectUuid,
            @Valid @RequestBody DraftConnectionTestRequest request) {
        authorization.requireProjectPermission(projectUuid, DISCOVERY_WRITE);
        boolean jdbc = "JDBC".equals(request.mode());
        boolean jndi = "JNDI".equals(request.mode());
        if (!jdbc && !jndi || jdbc && (request.jdbc() == null || request.jndi() != null)
                || jndi && (request.jndi() == null || request.jdbc() != null)) {
            throw new ApiException(
                    org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT,
                    "VALIDATION_FAILED", "Oracle taslak bağlantı modu geçersiz.");
        }
        JdbcDraft jdbcDraft = request.jdbc();
        if (jdbc && (!"TCP".equals(jdbcDraft.transport())
                || !"ENV".equals(jdbcDraft.credentialProvider())
                || !("SERVICE_NAME".equals(jdbcDraft.connectIdentifier().type())
                || "SID".equals(jdbcDraft.connectIdentifier().type())))) {
            throw new ApiException(
                    org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT,
                    "VALIDATION_FAILED", "Oracle JDBC taslak bağlantı alanları geçersiz.");
        }
        ConnectionProbe probe = service.testDraftConnection(
                request.mode(), jndi ? request.jndi().name() : null,
                jdbc ? jdbcDraft.host() : null,
                jdbc && "SERVICE_NAME".equals(jdbcDraft.connectIdentifier().type())
                        ? jdbcDraft.connectIdentifier().value() : null,
                jdbc && "SID".equals(jdbcDraft.connectIdentifier().type())
                        ? jdbcDraft.connectIdentifier().value() : null,
                jdbc ? jdbcDraft.port() : null,
                request.executionPolicy(),
                jdbc ? jdbcDraft.credentialReferencePath() : null);
        return DraftConnectionTestView.from(probe);
    }

    record DraftConnectionTestRequest(
            @NotBlank String mode,
            @Valid JdbcDraft jdbc,
            @Valid JndiDraft jndi,
            @NotNull @Min(2) @Max(2) Integer policyVersion,
            @NotNull JsonNode executionPolicy) {
        @JsonAnySetter
        void rejectUnknown(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown Oracle draft test field: " + field);
        }
    }

    record JdbcDraft(
            @NotBlank String host,
            @NotNull @Min(1) @Max(65535) Integer port,
            @Valid @NotNull ConnectIdentifierDraft connectIdentifier,
            @NotBlank String transport,
            @NotBlank String credentialProvider,
            @NotBlank String credentialReferencePath) {
        @JsonAnySetter
        void rejectUnknown(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown Oracle JDBC draft field: " + field);
        }
    }

    record ConnectIdentifierDraft(@NotBlank String type, @NotBlank String value) {
        @JsonAnySetter
        void rejectUnknown(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown Oracle identifier field: " + field);
        }
    }

    record JndiDraft(@NotBlank String name) {
        @JsonAnySetter
        void rejectUnknown(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown Oracle JNDI draft field: " + field);
        }
    }

    record DraftConnectionTestView(
            boolean connected,
            boolean oracle19cCompatible,
            String databaseProduct,
            String databaseVersion,
            int databaseMajorVersion,
            int databaseMinorVersion,
            String driverName,
            String driverVersion) {
        static DraftConnectionTestView from(ConnectionProbe probe) {
            return new DraftConnectionTestView(
                    true, true, probe.databaseProduct(), probe.databaseVersion(),
                    probe.databaseMajorVersion(), probe.databaseMinorVersion(),
                    probe.driverName(), probe.driverVersion());
        }
    }
}
