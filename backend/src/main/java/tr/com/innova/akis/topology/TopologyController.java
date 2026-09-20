package tr.com.innova.akis.topology;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import tr.com.innova.akis.oracle.OracleDiscoveryModels.ConnectionProbe;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.DraftConnection;
import tr.com.innova.akis.oracle.OracleDiscoveryService;
import tr.com.innova.akis.security.AuthorizationService;
import tr.com.innova.akis.topology.TopologyModels.ConnectionCatalogRow;
import tr.com.innova.akis.topology.TopologyModels.ConnectionDependencyRow;
import tr.com.innova.akis.topology.TopologyModels.ConnectionRow;
import tr.com.innova.akis.topology.TopologyModels.ConnectionTestRow;
import tr.com.innova.akis.topology.TopologyModels.EnvironmentRow;
import tr.com.innova.akis.topology.TopologyModels.LogicalSchemaRow;
import tr.com.innova.akis.topology.TopologyModels.PhysicalSchemaRow;
import tr.com.innova.akis.topology.TopologyModels.SchemaBindingRow;
import tr.com.innova.akis.topology.TopologyService.ConnectionInput;
import tr.com.innova.akis.topology.TopologyService.PhysicalSchemaInput;
import static tr.com.innova.akis.security.PermissionCodes.*;

/**
 * Topology is global (ODI master-repository model). The project in the path only scopes
 * the caller's permission check; the data itself is shared by all projects.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectUuid}")
final class TopologyController {

    private final TopologyService service;
    private final AuthorizationService authorization;
    private final OracleDiscoveryService discovery;

    TopologyController(TopologyService service, AuthorizationService authorization, OracleDiscoveryService discovery) {
        this.service = service;
        this.authorization = authorization;
        this.discovery = discovery;
    }

    // ---------------------------------------------------------------- connections

    @PostMapping("/connections")
    ResponseEntity<ConnectionView> createConnection(@PathVariable UUID projectUuid, @Valid @RequestBody ConnectionRequest request) {
        authorization.requireProjectPermission(projectUuid, TOPOLOGY_WRITE);
        ConnectionRow row = service.createConnection(request.toInput());
        return ResponseEntity.created(URI.create("/api/v1/projects/" + projectUuid + "/connections/" + row.uuid()))
                .body(ConnectionView.from(row));
    }

    @PostMapping("/connections/test")
    ConnectionProbeView testDraftConnection(@PathVariable UUID projectUuid, @Valid @RequestBody ConnectionRequest request) {
        authorization.requireProjectPermission(projectUuid, DISCOVERY_WRITE);
        return ConnectionProbeView.from(discovery.testDraftConnection(request.toDraft()));
    }

    @GetMapping("/connections")
    List<ConnectionView> listConnections(@PathVariable UUID projectUuid) {
        authorization.requireProjectPermission(projectUuid, TOPOLOGY_READ);
        return service.listConnections().stream().map(ConnectionView::from).toList();
    }

    @GetMapping("/connections/catalog")
    List<ConnectionCatalogView> connectionCatalog(@PathVariable UUID projectUuid) {
        authorization.requireProjectPermission(projectUuid, TOPOLOGY_READ);
        return service.listConnectionCatalog().stream().map(ConnectionCatalogView::from).toList();
    }

    @GetMapping("/connections/{connectionUuid}")
    ConnectionView connection(@PathVariable UUID projectUuid, @PathVariable UUID connectionUuid) {
        authorization.requireProjectPermission(projectUuid, TOPOLOGY_READ);
        return ConnectionView.from(service.connection(connectionUuid));
    }

    @GetMapping("/connections/{connectionUuid}/dependencies")
    List<ConnectionDependencyView> connectionDependencies(@PathVariable UUID projectUuid, @PathVariable UUID connectionUuid) {
        authorization.requireProjectPermission(projectUuid, TOPOLOGY_READ);
        return service.listConnectionDependencies(connectionUuid).stream().map(ConnectionDependencyView::from).toList();
    }

    @PatchMapping("/connections/{connectionUuid}")
    ConnectionView updateConnection(@PathVariable UUID projectUuid, @PathVariable UUID connectionUuid,
            @Valid @RequestBody ConnectionRequest request) {
        authorization.requireProjectPermission(projectUuid, TOPOLOGY_WRITE);
        return ConnectionView.from(service.updateConnection(connectionUuid, request.toInput()));
    }

    @DeleteMapping("/connections/{connectionUuid}")
    ResponseEntity<Void> deleteConnection(@PathVariable UUID projectUuid, @PathVariable UUID connectionUuid) {
        authorization.requireProjectPermission(projectUuid, TOPOLOGY_WRITE);
        service.deleteConnection(connectionUuid);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/connections/{connectionUuid}/tests")
    ResponseEntity<ConnectionTestView> testConnection(@PathVariable UUID projectUuid, @PathVariable UUID connectionUuid) {
        authorization.requireProjectPermission(projectUuid, DISCOVERY_WRITE);
        return ResponseEntity.status(HttpStatus.CREATED).body(ConnectionTestView.from(service.testConnection(connectionUuid)));
    }

    @GetMapping("/connections/{connectionUuid}/tests")
    List<ConnectionTestView> listTests(@PathVariable UUID projectUuid, @PathVariable UUID connectionUuid,
            @RequestParam(defaultValue = "20") int limit) {
        authorization.requireProjectPermission(projectUuid, TOPOLOGY_READ);
        return service.listTests(connectionUuid, limit).stream().map(ConnectionTestView::from).toList();
    }

    // ---------------------------------------------------------------- physical schemas

    @PostMapping("/physical-schemas")
    ResponseEntity<PhysicalSchemaView> createPhysicalSchema(@PathVariable UUID projectUuid, @Valid @RequestBody PhysicalSchemaRequest request) {
        authorization.requireProjectPermission(projectUuid, TOPOLOGY_WRITE);
        PhysicalSchemaRow row = service.createPhysicalSchema(request.toInput());
        return ResponseEntity.created(URI.create("/api/v1/projects/" + projectUuid + "/physical-schemas/" + row.uuid()))
                .body(PhysicalSchemaView.from(row));
    }

    @GetMapping("/physical-schemas")
    List<PhysicalSchemaView> listPhysicalSchemas(@PathVariable UUID projectUuid) {
        authorization.requireProjectPermission(projectUuid, TOPOLOGY_READ);
        return service.listPhysicalSchemas().stream().map(PhysicalSchemaView::from).toList();
    }

    @PatchMapping("/physical-schemas/{uuid}")
    PhysicalSchemaView updatePhysicalSchema(@PathVariable UUID projectUuid, @PathVariable UUID uuid,
            @Valid @RequestBody PhysicalSchemaRequest request) {
        authorization.requireProjectPermission(projectUuid, TOPOLOGY_WRITE);
        return PhysicalSchemaView.from(service.updatePhysicalSchema(uuid, request.toInput()));
    }

    @DeleteMapping("/physical-schemas/{uuid}")
    ResponseEntity<Void> deletePhysicalSchema(@PathVariable UUID projectUuid, @PathVariable UUID uuid) {
        authorization.requireProjectPermission(projectUuid, TOPOLOGY_WRITE);
        service.deletePhysicalSchema(uuid);
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------- logical schemas

    @PostMapping("/logical-schemas")
    ResponseEntity<LogicalSchemaView> createLogicalSchema(@PathVariable UUID projectUuid, @Valid @RequestBody CreateLogicalSchemaRequest request) {
        authorization.requireProjectPermission(projectUuid, TOPOLOGY_WRITE);
        LogicalSchemaRow row = service.createLogicalSchema(request.code(), request.name(), request.description(),
                request.databaseType(), request.environmentUuid(), request.physicalSchemaUuid());
        return ResponseEntity.created(URI.create("/api/v1/projects/" + projectUuid + "/logical-schemas/" + row.uuid()))
                .body(LogicalSchemaView.from(row));
    }

    @GetMapping("/logical-schemas")
    List<LogicalSchemaView> listLogicalSchemas(@PathVariable UUID projectUuid) {
        authorization.requireProjectPermission(projectUuid, TOPOLOGY_READ);
        return service.listLogicalSchemas().stream().map(LogicalSchemaView::from).toList();
    }

    @PatchMapping("/logical-schemas/{uuid}")
    LogicalSchemaView updateLogicalSchema(@PathVariable UUID projectUuid, @PathVariable UUID uuid, @Valid @RequestBody UpdateContextRequest request) {
        authorization.requireProjectPermission(projectUuid, TOPOLOGY_WRITE);
        return LogicalSchemaView.from(service.updateLogicalSchema(uuid, request.name(), request.description(), request.status()));
    }

    @DeleteMapping("/logical-schemas/{uuid}")
    ResponseEntity<Void> deleteLogicalSchema(@PathVariable UUID projectUuid, @PathVariable UUID uuid) {
        authorization.requireProjectPermission(projectUuid, TOPOLOGY_WRITE);
        service.deleteLogicalSchema(uuid);
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------- environments

    @PostMapping("/environments")
    ResponseEntity<EnvironmentView> createEnvironment(@PathVariable UUID projectUuid, @Valid @RequestBody CreateEnvironmentRequest request) {
        authorization.requireProjectPermission(projectUuid, TOPOLOGY_WRITE);
        EnvironmentRow row = service.createEnvironment(request.code(), request.name(), request.description(), request.risk(),
                Boolean.TRUE.equals(request.defaultEnvironment()), request.policy());
        return ResponseEntity.created(URI.create("/api/v1/projects/" + projectUuid + "/environments/" + row.uuid()))
                .body(EnvironmentView.from(row));
    }

    @GetMapping("/environments")
    List<EnvironmentView> listEnvironments(@PathVariable UUID projectUuid) {
        authorization.requireProjectPermission(projectUuid, TOPOLOGY_READ);
        return service.listEnvironments().stream().map(EnvironmentView::from).toList();
    }

    @PatchMapping("/environments/{uuid}")
    EnvironmentView updateEnvironment(@PathVariable UUID projectUuid, @PathVariable UUID uuid, @Valid @RequestBody UpdateEnvironmentRequest request) {
        authorization.requireProjectPermission(projectUuid, TOPOLOGY_WRITE);
        return EnvironmentView.from(service.updateEnvironment(uuid, request.name(), request.description(), request.risk(),
                request.defaultEnvironment(), request.status()));
    }

    @DeleteMapping("/environments/{uuid}")
    ResponseEntity<Void> deleteEnvironment(@PathVariable UUID projectUuid, @PathVariable UUID uuid) {
        authorization.requireProjectPermission(projectUuid, TOPOLOGY_WRITE);
        service.deleteEnvironment(uuid);
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------- schema bindings

    @PostMapping("/schema-bindings")
    ResponseEntity<SchemaBindingRow> createSchemaBinding(@PathVariable UUID projectUuid, @Valid @RequestBody SchemaBindingRequest request) {
        authorization.requireProjectPermission(projectUuid, TOPOLOGY_WRITE);
        return ResponseEntity.status(201).body(service.createSchemaBinding(
                request.logicalSchemaUuid(), request.environmentUuid(), request.physicalSchemaUuid()));
    }

    @GetMapping("/schema-bindings")
    List<SchemaBindingRow> listSchemaBindings(@PathVariable UUID projectUuid) {
        authorization.requireProjectPermission(projectUuid, TOPOLOGY_READ);
        return service.listSchemaBindings();
    }

    @PatchMapping("/schema-bindings/{bindingUuid}")
    SchemaBindingRow updateSchemaBinding(@PathVariable UUID projectUuid, @PathVariable UUID bindingUuid, @Valid @RequestBody SchemaBindingRequest request) {
        authorization.requireProjectPermission(projectUuid, TOPOLOGY_WRITE);
        return service.updateSchemaBinding(bindingUuid, request.logicalSchemaUuid(), request.environmentUuid(), request.physicalSchemaUuid());
    }

    @DeleteMapping("/schema-bindings/{bindingUuid}")
    ResponseEntity<Void> deleteSchemaBinding(@PathVariable UUID projectUuid, @PathVariable UUID bindingUuid) {
        authorization.requireProjectPermission(projectUuid, TOPOLOGY_WRITE);
        service.deleteSchemaBinding(bindingUuid);
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------- requests

    record ConnectionRequest(
            @NotBlank String code, @NotBlank String name, String description, @NotBlank String databaseType,
            String mode, String driverReference, String host, Integer port, String serviceName, String sid,
            String databaseName, String jdbcUrlExtra, String jndiName, String username, String password,
            Integer fetchSize, Integer batchSize, Integer connectTimeoutMs, Integer readTimeoutMs,
            Integer queryTimeoutSeconds, String onConnectSql, String onDisconnectSql, String status) {
        ConnectionInput toInput() {
            return new ConnectionInput(code, name, description, databaseType, mode, driverReference, host, port,
                    serviceName, sid, databaseName, jdbcUrlExtra, jndiName, username, password, fetchSize, batchSize,
                    connectTimeoutMs, readTimeoutMs, queryTimeoutSeconds, onConnectSql, onDisconnectSql, status);
        }
        DraftConnection toDraft() {
            return new DraftConnection(databaseType, mode, driverReference, host, port, serviceName, sid, databaseName,
                    jndiName, username, password, connectTimeoutMs, readTimeoutMs, queryTimeoutSeconds);
        }
    }

    record PhysicalSchemaRequest(
            UUID connectionUuid, String code, String name, String description, String catalogName,
            @NotBlank String schemaName, String workCatalogName, String workSchemaName, Boolean defaultSchema,
            String loadingPrefix, String integrationPrefix, String errorPrefix, String tempPrefix,
            String objectPattern, String remoteObjectPattern, String sequencePattern, String status) {
        PhysicalSchemaInput toInput() {
            return new PhysicalSchemaInput(connectionUuid, code, name, description, catalogName, schemaName,
                    workCatalogName, workSchemaName, defaultSchema, loadingPrefix, integrationPrefix, errorPrefix,
                    tempPrefix, objectPattern, remoteObjectPattern, sequencePattern, status);
        }
    }

    record CreateLogicalSchemaRequest(@NotBlank String code, @NotBlank String name, String description,
            String databaseType, UUID environmentUuid, UUID physicalSchemaUuid) {
    }

    record UpdateContextRequest(@NotBlank String name, String description, String status) {
    }

    record CreateEnvironmentRequest(@NotBlank String code, @NotBlank String name, String description, String risk,
            Boolean defaultEnvironment, JsonNode policy) {
    }

    record UpdateEnvironmentRequest(@NotBlank String name, String description, String risk, Boolean defaultEnvironment, String status) {
    }

    record SchemaBindingRequest(@NotNull UUID logicalSchemaUuid, @NotNull UUID environmentUuid, @NotNull UUID physicalSchemaUuid) {
    }

    // ---------------------------------------------------------------- views

    record ConnectionView(
            UUID uuid, String code, String name, String description, String databaseType, String mode,
            String driverReference, String host, Integer port, String serviceName, String sid, String databaseName,
            String jdbcUrlExtra, String jndiName, String username, boolean hasPassword,
            int fetchSize, int batchSize, int connectTimeoutMs, int readTimeoutMs, int queryTimeoutSeconds,
            String onConnectSql, String onDisconnectSql, OffsetDateTime lastTestedAt, Boolean lastTestPassed,
            String status, String createdBy, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        static ConnectionView from(ConnectionRow r) {
            return new ConnectionView(r.uuid(), r.code(), r.name(), r.description(), r.databaseType(), r.mode(),
                    r.driverReference(), r.host(), r.port(), r.serviceName(), r.sid(), r.databaseName(),
                    r.jdbcUrlExtra(), r.jndiName(), r.username(), r.hasPassword(), r.fetchSize(), r.batchSize(),
                    r.connectTimeoutMs(), r.readTimeoutMs(), r.queryTimeoutSeconds(), r.onConnectSql(),
                    r.onDisconnectSql(), r.lastTestedAt(), r.lastTestPassed(), r.status(), r.createdBy(),
                    r.createdAt(), r.updatedAt());
        }
    }

    record ConnectionCatalogView(ConnectionView connection, int physicalSchemaCount, int logicalSchemaCount) {
        static ConnectionCatalogView from(ConnectionCatalogRow row) {
            return new ConnectionCatalogView(ConnectionView.from(row.connection()), row.physicalSchemaCount(), row.logicalSchemaCount());
        }
    }

    record ConnectionDependencyView(UUID uuid, String type, String name) {
        static ConnectionDependencyView from(ConnectionDependencyRow row) {
            return new ConnectionDependencyView(row.uuid(), row.type(), row.name());
        }
    }

    record ConnectionTestView(
            UUID uuid, int attemptNumber, String outcome, String errorCode, ConnectionProbeView probe,
            OffsetDateTime startedAt, OffsetDateTime completedAt, long durationMs) {
        static ConnectionTestView from(ConnectionTestRow r) {
            ConnectionProbeView probe = r.databaseProduct() == null ? null : new ConnectionProbeView(
                    true, r.databaseProduct(), r.databaseVersion(), r.databaseMajorVersion(), r.databaseMinorVersion(),
                    r.driverName(), r.driverVersion());
            return new ConnectionTestView(r.uuid(), r.attemptNumber(), r.outcome(), r.errorCode(), probe,
                    r.startedAt(), r.completedAt(), r.durationMs());
        }
    }

    record ConnectionProbeView(boolean connected, String databaseProduct, String databaseVersion,
            Integer databaseMajorVersion, Integer databaseMinorVersion, String driverName, String driverVersion) {
        static ConnectionProbeView from(ConnectionProbe p) {
            return new ConnectionProbeView(true, p.databaseProduct(), p.databaseVersion(), p.databaseMajorVersion(),
                    p.databaseMinorVersion(), p.driverName(), p.driverVersion());
        }
    }

    record PhysicalSchemaView(
            UUID uuid, UUID connectionUuid, String code, String name, String description, String databaseType,
            String catalogName, String schemaName, String workCatalogName, String workSchemaName, boolean defaultSchema,
            String loadingPrefix, String integrationPrefix, String errorPrefix, String tempPrefix,
            String objectPattern, String remoteObjectPattern, String sequencePattern, String status) {
        static PhysicalSchemaView from(PhysicalSchemaRow r) {
            return new PhysicalSchemaView(r.uuid(), r.connectionUuid(), r.code(), r.name(), r.description(),
                    r.databaseType(), r.catalogName(), r.schemaName(), r.workCatalogName(), r.workSchemaName(),
                    r.defaultSchema(), r.loadingPrefix(), r.integrationPrefix(), r.errorPrefix(), r.tempPrefix(),
                    r.objectPattern(), r.remoteObjectPattern(), r.sequencePattern(), r.status());
        }
    }

    record LogicalSchemaView(UUID uuid, String code, String name, String description, String databaseType, String status) {
        static LogicalSchemaView from(LogicalSchemaRow r) {
            return new LogicalSchemaView(r.uuid(), r.code(), r.name(), r.description(), r.databaseType(), r.status());
        }
    }

    record EnvironmentView(UUID uuid, String code, String name, String description, String risk,
            boolean defaultEnvironment, int policyVersion, JsonNode policy, String status) {
        static EnvironmentView from(EnvironmentRow r) {
            return new EnvironmentView(r.uuid(), r.code(), r.name(), r.description(), r.risk(), r.defaultEnvironment(),
                    r.policyVersion(), r.policy(), r.status());
        }
    }
}
