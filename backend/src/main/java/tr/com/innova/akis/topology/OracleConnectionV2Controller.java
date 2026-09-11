package tr.com.innova.akis.topology;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import tr.com.innova.akis.metadata.ApiException;
import tr.com.innova.akis.security.AuthorizationService;
import tr.com.innova.akis.topology.TopologyModels.ConnectionVersionRow;
import static tr.com.innova.akis.security.PermissionCodes.TOPOLOGY_READ;
import static tr.com.innova.akis.security.PermissionCodes.TOPOLOGY_WRITE;

/** Versioned Oracle-specific connection contract. V1 remains JDBC compatible. */
@RestController
@RequestMapping("/api/v2/projects/{projectUuid}/connections/{connectionUuid}/versions")
final class OracleConnectionV2Controller {

    private static final Set<String> MODES = Set.of("JDBC", "JNDI");
    private static final Set<String> IDENTIFIER_TYPES = Set.of("SERVICE_NAME", "SID");
    private static final Set<String> TRANSPORTS = Set.of("TCP");
    private static final Set<String> POLICY_FIELDS = Set.of(
            "connectTimeoutMs", "readTimeoutMs", "networkTimeoutMs", "queryTimeoutSeconds");

    private final TopologyService service;
    private final AuthorizationService authorization;

    OracleConnectionV2Controller(
            TopologyService service, AuthorizationService authorization) {
        this.service = service;
        this.authorization = authorization;
    }

    @PostMapping
    ResponseEntity<ConnectionVersionV2View> create(
            @PathVariable UUID projectUuid,
            @PathVariable UUID connectionUuid,
            @Valid @RequestBody CreateOracleConnectionVersionV2Request request) {
        authorization.requireProjectPermission(projectUuid, TOPOLOGY_WRITE);
        requireOracle(projectUuid, connectionUuid);
        String mode = allowed(request.mode(), MODES, "connection mode");
        ConnectionVersionRow row;
        int policyVersion = request.policyVersion() == null ? 2 : request.policyVersion();
        if (policyVersion != 2) {
            throw validation("Oracle connection V2 requires policyVersion 2.");
        }
        if (request.executionPolicy() != null
                && (!request.executionPolicy().isObject()
                || !request.executionPolicy().propertyNames().stream().allMatch(POLICY_FIELDS::contains))) {
            throw validation("Oracle connection V2 executionPolicy contains unsupported fields.");
        }
        if ("JDBC".equals(mode)) {
            if (request.jdbc() == null || request.jndi() != null) {
                throw validation("JDBC mode requires only the jdbc payload.");
            }
            JdbcRequest jdbc = request.jdbc();
            if (jdbc.connectIdentifier() == null) {
                throw validation("JDBC connectIdentifier is required.");
            }
            String identifierType = allowed(
                    jdbc.connectIdentifier().type(), IDENTIFIER_TYPES, "connect identifier type");
            String transport = allowed(jdbc.transport(), TRANSPORTS, "transport");
            String serviceName = "SERVICE_NAME".equals(identifierType)
                    ? jdbc.connectIdentifier().value() : null;
            String sid = "SID".equals(identifierType)
                    ? jdbc.connectIdentifier().value() : null;
            row = service.createConnectionVersion(
                    projectUuid, connectionUuid, "JDBC", null, jdbc.host(),
                    serviceName, sid, null,
                    "DISABLED",
                    jdbc.port(), null, policyVersion, request.executionPolicy(),
                    jdbc.credentialSecretReferenceUuid(), "KIMLIK");
        }
        else {
            if (request.jndi() == null || request.jdbc() != null) {
                throw validation("JNDI mode requires only the jndi payload.");
            }
            row = service.createConnectionVersion(
                    projectUuid, connectionUuid, "JNDI", null, null,
                    null, null, null, null, null, request.jndi().name(),
                    policyVersion, request.executionPolicy(), null, null);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(ConnectionVersionV2View.from(row));
    }

    @GetMapping
    List<ConnectionVersionV2View> list(
            @PathVariable UUID projectUuid,
            @PathVariable UUID connectionUuid) {
        authorization.requireProjectPermission(projectUuid, TOPOLOGY_READ);
        requireOracle(projectUuid, connectionUuid);
        return service.listConnectionVersions(projectUuid, connectionUuid).stream()
                .map(ConnectionVersionV2View::from)
                .toList();
    }

    private String allowed(String raw, Set<String> values, String field) {
        if (raw == null || !values.contains(raw)) {
            throw validation("Invalid " + field + ".");
        }
        return raw;
    }

    private void requireOracle(UUID projectUuid, UUID connectionUuid) {
        if (!"ORACLE".equals(service.connection(projectUuid, connectionUuid).databaseType())) {
            throw validation("Oracle connection V2 is available only for Oracle connections.");
        }
    }

    private ApiException validation(String message) {
        return new ApiException(
                HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED", message);
    }

    record CreateOracleConnectionVersionV2Request(
            @NotBlank String mode,
            @Valid JdbcRequest jdbc,
            @Valid JndiRequest jndi,
            @Min(1) Integer policyVersion,
            JsonNode executionPolicy) {
        @JsonAnySetter
        void rejectUnknown(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown Oracle connection V2 field: " + field);
        }
    }

    record JdbcRequest(
            @NotBlank String host,
            @NotNull @Min(1) @Max(65535) Integer port,
            @Valid @NotNull ConnectIdentifierRequest connectIdentifier,
            @NotBlank String transport,
            @NotNull UUID credentialSecretReferenceUuid) {
        @JsonAnySetter
        void rejectUnknown(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown Oracle JDBC V2 field: " + field);
        }
    }

    record ConnectIdentifierRequest(
            @NotBlank String type,
            @NotBlank String value) {
        @JsonAnySetter
        void rejectUnknown(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown Oracle connect identifier field: " + field);
        }
    }

    record JndiRequest(@NotBlank String name) {
        @JsonAnySetter
        void rejectUnknown(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown Oracle JNDI V2 field: " + field);
        }
    }

    record ConnectionVersionV2View(
            UUID uuid,
            int versionNumber,
            String mode,
            String driverReference,
            String host,
            String serviceName,
            String sid,
            String databaseName,
            String jndiName,
            String tlsMode,
            Integer port,
            int policyVersion,
            JsonNode policy,
            OffsetDateTime createdAt) {

        static ConnectionVersionV2View from(ConnectionVersionRow row) {
            return new ConnectionVersionV2View(
                    row.uuid(), row.versionNumber(), row.mode(), row.driverReference(), row.host(),
                    row.serviceName(), row.sid(), row.databaseName(), row.jndiName(), row.tlsMode(),
                    row.port(), row.policyVersion(), row.policy(), row.createdAt());
        }
    }
}
