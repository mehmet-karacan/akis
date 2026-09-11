package tr.com.innova.akis.oracle;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tr.com.innova.akis.oracle.OracleDiscoveryModels.ColumnMetadata;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.ConstraintMetadata;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.DiscoveryResult;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.TableMetadata;
import tr.com.innova.akis.security.AuthorizationService;
import static tr.com.innova.akis.security.PermissionCodes.DISCOVERY_WRITE;

@RestController
@RequestMapping("/api/v1/projects/{projectUuid}/connections/{connectionUuid}/versions/{connectionVersionUuid}")
final class OracleDiscoveryController {

    private final OracleDiscoveryService service;
    private final OracleConnectionLifecycleService lifecycleService;
    private final AuthorizationService authorization;

    OracleDiscoveryController(
            OracleDiscoveryService service,
            OracleConnectionLifecycleService lifecycleService,
            AuthorizationService authorization) {
        this.service = service;
        this.lifecycleService = lifecycleService;
        this.authorization = authorization;
    }

    @PostMapping("/test")
    ConnectionTestView testConnection(
            @PathVariable UUID projectUuid,
            @PathVariable UUID connectionUuid,
            @PathVariable UUID connectionVersionUuid) {
        authorization.requireProjectPermission(projectUuid, DISCOVERY_WRITE);
        return ConnectionTestView.from(lifecycleService.test(
                projectUuid, connectionUuid, connectionVersionUuid));
    }

    @PostMapping("/physical-schemas/{physicalSchemaUuid}/discover")
    DiscoveryView discover(
            @PathVariable UUID projectUuid,
            @PathVariable UUID connectionUuid,
            @PathVariable UUID connectionVersionUuid,
            @PathVariable UUID physicalSchemaUuid,
            @Valid @RequestBody(required = false) DiscoveryRequest request) {
        authorization.requireProjectPermission(projectUuid, DISCOVERY_WRITE);
        DiscoveryRequest safeRequest = request == null
                ? new DiscoveryRequest(null, 100)
                : request;
        int limit = safeRequest.limit() == null ? 100 : safeRequest.limit();
        DiscoveryResult result = service.discover(
                projectUuid,
                connectionUuid,
                connectionVersionUuid,
                physicalSchemaUuid,
                safeRequest.tableName(),
                limit);
        return DiscoveryView.from(connectionVersionUuid, physicalSchemaUuid, result);
    }

    record DiscoveryRequest(
            @Pattern(regexp = "[A-Za-z][A-Za-z0-9_$#]{0,127}") String tableName,
            @Min(1) @Max(200) Integer limit) {
    }

    record ConnectionTestView(
            UUID connectionVersionUuid,
            boolean connected,
            boolean oracle19cCompatible,
            String databaseProduct,
            String databaseVersion,
            int databaseMajorVersion,
            int databaseMinorVersion,
            String driverName,
            String driverVersion) {

        static ConnectionTestView from(
                OracleConnectionLifecycleModels.TestAttemptRow attempt) {
            return new ConnectionTestView(
                    attempt.connectionVersionUuid(),
                    true,
                    true,
                    attempt.databaseProduct(),
                    attempt.databaseVersion(),
                    attempt.databaseMajorVersion(),
                    attempt.databaseMinorVersion(),
                    attempt.driverName(),
                    attempt.driverVersion());
        }
    }

    record DiscoveryView(
            UUID connectionVersionUuid,
            UUID physicalSchemaUuid,
            String owner,
            OffsetDateTime discoveredAt,
            boolean truncated,
            List<TableView> tables) {

        static DiscoveryView from(
                UUID connectionVersionUuid,
                UUID physicalSchemaUuid,
                DiscoveryResult result) {
            return new DiscoveryView(
                    connectionVersionUuid,
                    physicalSchemaUuid,
                    result.owner(),
                    result.discoveredAt(),
                    result.truncated(),
                    result.tables().stream().map(TableView::from).toList());
        }
    }

    record TableView(
            String owner,
            String name,
            String type,
            List<ColumnView> columns,
            List<ConstraintView> constraints) {

        static TableView from(TableMetadata table) {
            return new TableView(
                    table.owner(),
                    table.name(),
                    table.type(),
                    table.columns().stream().map(ColumnView::from).toList(),
                    table.constraints().stream().map(ConstraintView::from).toList());
        }
    }

    record ColumnView(
            String name,
            int jdbcType,
            String producerType,
            String canonicalType,
            String executionCapability,
            int ordinal,
            Integer precision,
            Integer scale,
            boolean nullable,
            String defaultExpression) {

        static ColumnView from(ColumnMetadata column) {
            OracleColumnCapability.Classification capability =
                    OracleColumnCapability.classify(
                            column.producerType(), column.precision(), column.scale());
            return new ColumnView(
                    column.name(), column.jdbcType(), column.producerType(),
                    capability.canonicalType(), capability.executionCapability(), column.ordinal(),
                    column.precision(), column.scale(), column.nullable(),
                    column.defaultExpression());
        }
    }

    record ConstraintView(
            String name,
            String type,
            List<String> columns,
            String referencedOwner,
            String referencedTable) {

        static ConstraintView from(ConstraintMetadata constraint) {
            return new ConstraintView(
                    constraint.name(), constraint.type(), constraint.columns(),
                    constraint.referencedOwner(), constraint.referencedTable());
        }
    }
}
