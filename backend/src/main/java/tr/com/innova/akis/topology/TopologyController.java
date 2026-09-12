package tr.com.innova.akis.topology;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import tr.com.innova.akis.topology.TopologyModels.ConnectionRow;
import tr.com.innova.akis.topology.TopologyModels.ConnectionVersionRow;
import tr.com.innova.akis.topology.TopologyModels.EnvironmentRow;
import tr.com.innova.akis.topology.TopologyModels.LogicalSchemaRow;
import tr.com.innova.akis.topology.TopologyModels.PhysicalSchemaRow;
import tr.com.innova.akis.topology.TopologyModels.SchemaBindingRow;
import tr.com.innova.akis.security.AuthorizationService;
import static tr.com.innova.akis.security.PermissionCodes.*;

@RestController
@RequestMapping("/api/v1/projects/{projectUuid}")
final class TopologyController {

    private final TopologyService service;
    private final AuthorizationService authorization;

    TopologyController(TopologyService service, AuthorizationService authorization) {
        this.service = service;
        this.authorization = authorization;
    }

    @PostMapping("/connections")
    ResponseEntity<ConnectionView> createConnection(
            @PathVariable UUID projectUuid,
            @Valid @RequestBody CreateConnectionRequest request) {
        authorization.requireProjectPermission(projectUuid, TOPOLOGY_WRITE);
        ConnectionRow row = service.createConnection(
                projectUuid, request.code(), request.databaseType(),
                request.name(), request.description());
        return ResponseEntity.created(URI.create(
                "/api/v1/projects/" + projectUuid + "/connections/" + row.uuid()))
                .body(ConnectionView.from(row));
    }

    @GetMapping("/connections")
    List<ConnectionView> listConnections(@PathVariable UUID projectUuid) {
        authorization.requireProjectPermission(projectUuid, TOPOLOGY_READ);
        return service.listConnections(projectUuid).stream().map(ConnectionView::from).toList();
    }

    @GetMapping("/connections/{connectionUuid}")
    ConnectionView connection(
            @PathVariable UUID projectUuid,
            @PathVariable UUID connectionUuid) {
        authorization.requireProjectPermission(projectUuid, TOPOLOGY_READ);
        return ConnectionView.from(service.connection(projectUuid, connectionUuid));
    }

    @PostMapping("/connections/{connectionUuid}/versions")
    ResponseEntity<ConnectionVersionView> createConnectionVersion(
            @PathVariable UUID projectUuid,
            @PathVariable UUID connectionUuid,
            @Valid @RequestBody CreateConnectionVersionRequest request) {
        authorization.requireProjectPermission(projectUuid, TOPOLOGY_WRITE);
        ConnectionVersionRow row = service.createConnectionVersionWithCredential(
                projectUuid, connectionUuid, "JDBC", request.driverReference(), request.host(),
                request.serviceName(), request.sid(), request.databaseName(), request.tlsMode(),
                request.port(), null,
                request.policyVersion() == null ? 1 : request.policyVersion(),
                request.policy(), request.credentialProvider(), request.credentialReferencePath());
        return ResponseEntity.status(201).body(ConnectionVersionView.from(row));
    }

    @GetMapping("/connections/{connectionUuid}/versions")
    List<ConnectionVersionView> listConnectionVersions(
            @PathVariable UUID projectUuid,
            @PathVariable UUID connectionUuid) {
        authorization.requireProjectPermission(projectUuid, TOPOLOGY_READ);
        return service.listConnectionVersions(projectUuid, connectionUuid).stream()
                .filter(row -> "JDBC".equals(row.mode()))
                .map(ConnectionVersionView::from)
                .toList();
    }

    @PostMapping("/physical-schemas")
    ResponseEntity<PhysicalSchemaView> createPhysicalSchema(
            @PathVariable UUID projectUuid,
            @Valid @RequestBody CreatePhysicalSchemaRequest request) {
        authorization.requireProjectPermission(projectUuid, TOPOLOGY_WRITE);
        PhysicalSchemaRow row = service.createPhysicalSchema(
                projectUuid, request.connectionUuid(), request.code(),
                request.schemaReference(), request.name());
        return ResponseEntity.created(URI.create(
                "/api/v1/projects/" + projectUuid + "/physical-schemas/" + row.uuid()))
                .body(PhysicalSchemaView.from(row));
    }

    @GetMapping("/physical-schemas")
    List<PhysicalSchemaView> listPhysicalSchemas(@PathVariable UUID projectUuid) {
        authorization.requireProjectPermission(projectUuid, TOPOLOGY_READ);
        return service.listPhysicalSchemas(projectUuid).stream()
                .map(PhysicalSchemaView::from)
                .toList();
    }

    @PostMapping("/logical-schemas")
    ResponseEntity<LogicalSchemaView> createLogicalSchema(
            @PathVariable UUID projectUuid,
            @Valid @RequestBody CreateLogicalSchemaRequest request) {
        authorization.requireProjectPermission(projectUuid, TOPOLOGY_WRITE);
        LogicalSchemaRow row = service.createLogicalSchema(
                projectUuid, request.code(), request.name(), request.description());
        return ResponseEntity.created(URI.create(
                "/api/v1/projects/" + projectUuid + "/logical-schemas/" + row.uuid()))
                .body(LogicalSchemaView.from(row));
    }

    @GetMapping("/logical-schemas")
    List<LogicalSchemaView> listLogicalSchemas(@PathVariable UUID projectUuid) {
        authorization.requireProjectPermission(projectUuid, TOPOLOGY_READ);
        return service.listLogicalSchemas(projectUuid).stream()
                .map(LogicalSchemaView::from)
                .toList();
    }

    @PostMapping("/environments")
    ResponseEntity<EnvironmentView> createEnvironment(
            @PathVariable UUID projectUuid,
            @Valid @RequestBody CreateEnvironmentRequest request) {
        authorization.requireProjectPermission(projectUuid, TOPOLOGY_WRITE);
        EnvironmentRow row = service.createEnvironment(
                projectUuid, request.code(), request.risk(),
                request.policyVersion() == null ? 1 : request.policyVersion(),
                request.policy(), request.name());
        return ResponseEntity.created(URI.create(
                "/api/v1/projects/" + projectUuid + "/environments/" + row.uuid()))
                .body(EnvironmentView.from(row));
    }

    @GetMapping("/environments")
    List<EnvironmentView> listEnvironments(@PathVariable UUID projectUuid) {
        authorization.requireProjectPermission(projectUuid, TOPOLOGY_READ);
        return service.listEnvironments(projectUuid).stream()
                .map(EnvironmentView::from)
                .toList();
    }

    @PostMapping("/schema-bindings")
    ResponseEntity<SchemaBindingRow> createSchemaBinding(
            @PathVariable UUID projectUuid,
            @Valid @RequestBody CreateSchemaBindingRequest request) {
        authorization.requireProjectPermission(projectUuid, TOPOLOGY_WRITE);
        SchemaBindingRow row = service.createSchemaBinding(
                projectUuid, request.logicalSchemaUuid(), request.environmentUuid(),
                request.physicalSchemaUuid(), request.connectionVersionUuid());
        return ResponseEntity.status(201).body(row);
    }

    @GetMapping("/schema-bindings")
    List<SchemaBindingRow> listSchemaBindings(@PathVariable UUID projectUuid) {
        authorization.requireProjectPermission(projectUuid, TOPOLOGY_READ);
        return service.listSchemaBindings(projectUuid);
    }

    record CreateConnectionRequest(
            @NotBlank String code,
            @NotBlank String databaseType,
            @NotBlank String name,
            String description) {
    }

    record CreateConnectionVersionRequest(
            @NotBlank String driverReference,
            @NotBlank String host,
            String serviceName,
            String sid,
            String databaseName,
            String tlsMode,
            @Min(1) @Max(65535) int port,
            @Min(1) Integer policyVersion,
            JsonNode policy,
            String credentialProvider,
            String credentialReferencePath) {
    }

    record CreatePhysicalSchemaRequest(
            @NotNull UUID connectionUuid,
            @NotBlank String code,
            @NotBlank String schemaReference,
            @NotBlank String name) {
    }

    record CreateLogicalSchemaRequest(
            @NotBlank String code,
            @NotBlank String name,
            String description) {
    }

    record CreateEnvironmentRequest(
            @NotBlank String code,
            String risk,
            @Min(1) Integer policyVersion,
            JsonNode policy,
            @NotBlank String name) {
    }

    record CreateSchemaBindingRequest(
            @NotNull UUID logicalSchemaUuid,
            @NotNull UUID environmentUuid,
            @NotNull UUID physicalSchemaUuid,
            @NotNull UUID connectionVersionUuid) {
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

    record ConnectionVersionView(
            UUID uuid,
            int versionNumber,
            String driverReference,
            String host,
            String serviceName,
            String sid,
            String databaseName,
            String tlsMode,
            int port,
            int policyVersion,
            JsonNode policy,
            OffsetDateTime createdAt) {

        static ConnectionVersionView from(ConnectionVersionRow row) {
            return new ConnectionVersionView(
                    row.uuid(), row.versionNumber(), row.driverReference(), row.host(),
                    row.serviceName(), row.sid(), row.databaseName(), row.tlsMode(), row.port(),
                    row.policyVersion(), row.policy(), row.createdAt());
        }
    }

    record PhysicalSchemaView(
            UUID uuid,
            UUID connectionUuid,
            String code,
            String schemaReference,
            String status,
            String name,
            long version) {

        static PhysicalSchemaView from(PhysicalSchemaRow row) {
            return new PhysicalSchemaView(
                    row.uuid(), row.connectionUuid(), row.code(), row.schemaReference(),
                    row.status(), row.name(), row.version());
        }
    }

    record LogicalSchemaView(
            UUID uuid,
            String code,
            String status,
            String name,
            String description,
            long version) {

        static LogicalSchemaView from(LogicalSchemaRow row) {
            return new LogicalSchemaView(
                    row.uuid(), row.code(), row.status(), row.name(), row.description(),
                    row.version());
        }
    }

    record EnvironmentView(
            UUID uuid,
            String code,
            String risk,
            String status,
            int policyVersion,
            JsonNode policy,
            String name,
            long version) {

        static EnvironmentView from(EnvironmentRow row) {
            return new EnvironmentView(
                    row.uuid(), row.code(), row.risk(), row.status(), row.policyVersion(),
                    row.policy(), row.name(), row.version());
        }
    }
}
