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
import tr.com.innova.akis.oracle.OracleLocalCredentialStore;
import tr.com.innova.akis.security.AuthorizationService;
import tr.com.innova.akis.topology.TopologyModels.ConnectionRow;
import tr.com.innova.akis.topology.TopologyModels.ConnectionVersionRow;
import tr.com.innova.akis.topology.TopologyService.ConnectionWithInitialVersion;
import static tr.com.innova.akis.security.PermissionCodes.TOPOLOGY_READ;
import static tr.com.innova.akis.security.PermissionCodes.TOPOLOGY_WRITE;

/** Versioned Oracle-specific connection contract. V1 remains JDBC compatible. */
@RestController
@RequestMapping("/api/v2/projects/{projectUuid}/connections")
final class OracleConnectionV2Controller {

    private static final Set<String> MODES = Set.of("JDBC", "JNDI");
    private static final Set<String> IDENTIFIER_TYPES = Set.of("SERVICE_NAME", "SID");
    private static final Set<String> TRANSPORTS = Set.of("TCP");
    private static final Set<String> POLICY_FIELDS = Set.of(
            "connectTimeoutMs", "readTimeoutMs", "networkTimeoutMs", "queryTimeoutSeconds");

    private final TopologyService service;
    private final AuthorizationService authorization;
    private final OracleLocalCredentialStore credentialStore;

    OracleConnectionV2Controller(
            TopologyService service, AuthorizationService authorization,
            OracleLocalCredentialStore credentialStore) {
        this.service = service;
        this.authorization = authorization;
        this.credentialStore = credentialStore;
    }

    @PostMapping
    ResponseEntity<OracleConnectionWithInitialVersionV2View> createConnection(
            @PathVariable UUID projectUuid,
            @Valid @RequestBody CreateOracleConnectionV2Request request) {
        authorization.requireProjectPermission(projectUuid, TOPOLOGY_WRITE);
        VersionFields fields = versionFields(request.initialVersion());
        OracleLocalCredentialStore.StoredCredential stored = null;
        if ("JDBC".equals(fields.mode())) {
            if (request.credentials() == null) throw validation("JDBC credentials are required.");
            if (fields.credentialProvider() != null || fields.credentialReferencePath() != null) {
                throw validation("Credential references are managed by the platform.");
            }
            char[] password = request.credentials().password().toCharArray();
            try {
                stored = credentialStore.store(request.credentials().username(), password);
            }
            finally {
                java.util.Arrays.fill(password, '\0');
            }
        }
        else if (request.credentials() != null) {
            throw validation("JNDI credentials are managed by the application server.");
        }
        ConnectionWithInitialVersion created = service.createOracleConnectionWithInitialVersion(
                projectUuid, request.code(), request.name(), request.description(),
                fields.mode(), fields.host(), fields.serviceName(), fields.sid(), fields.port(),
                fields.jndiName(), fields.policyVersion(), fields.executionPolicy(),
                stored == null ? null : "ENV", stored == null ? null : stored.reference(),
                stored == null ? null : stored.username());
        return ResponseEntity.status(HttpStatus.CREATED).body(
                OracleConnectionWithInitialVersionV2View.from(created));
    }

    @PostMapping("/{connectionUuid}/versions")
    ResponseEntity<ConnectionVersionV2View> create(
            @PathVariable UUID projectUuid,
            @PathVariable UUID connectionUuid,
            @Valid @RequestBody CreateOracleConnectionVersionV2Request request) {
        authorization.requireProjectPermission(projectUuid, TOPOLOGY_WRITE);
        requireOracle(projectUuid, connectionUuid);
        VersionFields fields = versionFields(request);
        if ("JDBC".equals(fields.mode())
                && (fields.credentialProvider() == null || fields.credentialReferencePath() == null)) {
            throw validation("JDBC credential reference is required.");
        }
        ConnectionVersionRow row = service.createConnectionVersionWithCredential(
                projectUuid, connectionUuid, fields.mode(), null, fields.host(),
                fields.serviceName(), fields.sid(), null, "DISABLED", fields.port(),
                fields.jndiName(), fields.policyVersion(), fields.executionPolicy(),
                fields.credentialProvider(), fields.credentialReferencePath());
        return ResponseEntity.status(HttpStatus.CREATED).body(ConnectionVersionV2View.from(row));
    }

    @GetMapping("/{connectionUuid}/versions")
    List<ConnectionVersionV2View> list(
            @PathVariable UUID projectUuid,
            @PathVariable UUID connectionUuid) {
        authorization.requireProjectPermission(projectUuid, TOPOLOGY_READ);
        requireOracle(projectUuid, connectionUuid);
        return service.listConnectionVersions(projectUuid, connectionUuid).stream()
                .map(ConnectionVersionV2View::from)
                .toList();
    }

    private VersionFields versionFields(CreateOracleConnectionVersionV2Request request) {
        String mode = allowed(request.mode(), MODES, "connection mode");
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
            allowed(jdbc.transport(), TRANSPORTS, "transport");
            return new VersionFields(
                    mode, jdbc.host(),
                    "SERVICE_NAME".equals(identifierType) ? jdbc.connectIdentifier().value() : null,
                    "SID".equals(identifierType) ? jdbc.connectIdentifier().value() : null,
                    jdbc.port(), null, policyVersion, request.executionPolicy(),
                    jdbc.credentialProvider(), jdbc.credentialReferencePath());
        }
        if (request.jndi() == null || request.jdbc() != null) {
            throw validation("JNDI mode requires only the jndi payload.");
        }
        return new VersionFields(
                mode, null, null, null, null, request.jndi().name(), policyVersion,
                request.executionPolicy(), null, null);
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

    record CreateOracleConnectionV2Request(
            @NotBlank String code,
            @NotBlank String name,
            String description,
            @Valid @NotNull CreateOracleConnectionVersionV2Request initialVersion,
            @Valid CredentialsRequest credentials) {
        @JsonAnySetter
        void rejectUnknown(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown Oracle connection V2 field: " + field);
        }
    }

    private record VersionFields(
            String mode,
            String host,
            String serviceName,
            String sid,
            Integer port,
            String jndiName,
            int policyVersion,
            JsonNode executionPolicy,
            String credentialProvider,
            String credentialReferencePath) {
    }

    record OracleConnectionWithInitialVersionV2View(
            ConnectionView connection,
            ConnectionVersionV2View initialVersion) {
        static OracleConnectionWithInitialVersionV2View from(ConnectionWithInitialVersion created) {
            return new OracleConnectionWithInitialVersionV2View(
                    ConnectionView.from(created.connection()),
                    ConnectionVersionV2View.from(created.initialVersion()));
        }
    }

    record ConnectionView(
            UUID uuid,
            String code,
            String databaseType,
            String status,
            String name,
            String description,
            long version) {
        static ConnectionView from(ConnectionRow row) {
            return new ConnectionView(
                    row.uuid(), row.code(), row.databaseType(), row.status(), row.name(),
                    row.description(), row.version());
        }
    }

    record JdbcRequest(
            @NotBlank String host,
            @NotNull @Min(1) @Max(65535) Integer port,
            @Valid @NotNull ConnectIdentifierRequest connectIdentifier,
            @NotBlank String transport,
            String credentialProvider,
            String credentialReferencePath) {
        @JsonAnySetter
        void rejectUnknown(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown Oracle JDBC V2 field: " + field);
        }
    }

    record CredentialsRequest(@NotBlank String username, @NotBlank String password) {
        @JsonAnySetter
        void rejectUnknown(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown Oracle credential field: " + field);
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
            String username,
            String host,
            String serviceName,
            String sid,
            String databaseName,
            String jndiName,
            String tlsMode,
            Integer port,
            int policyVersion,
            JsonNode policy,
            OffsetDateTime createdAt,
            String lifecycleStatus,
            long lifecycleVersion,
            Integer targetIdentityVersion,
            String targetFingerprint,
            UUID latestSuccessfulTestUuid,
            OffsetDateTime testedAt,
            OffsetDateTime activatedAt,
            String runtimeCapability) {

        static ConnectionVersionV2View from(ConnectionVersionRow row) {
            return new ConnectionVersionV2View(
                    row.uuid(), row.versionNumber(), row.mode(), row.driverReference(), row.username(), row.host(),
                    row.serviceName(), row.sid(), row.databaseName(), row.jndiName(), row.tlsMode(),
                    row.port(), row.policyVersion(), row.policy(), row.createdAt(),
                    row.lifecycleStatus(), row.lifecycleVersion(), row.targetIdentityVersion(),
                    row.targetFingerprint(), row.latestSuccessfulTestUuid(), row.testedAt(),
                    row.activatedAt(), "JNDI".equals(row.mode())
                            ? "TEST_DISCOVERY_ONLY" : "EXECUTABLE");
        }
    }
}
