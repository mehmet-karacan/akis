package tr.com.innova.akis.oracle;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
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
@RequestMapping("/api/v1/projects/{projectUuid}/connections/{connectionUuid}")
final class OracleDiscoveryController {

    private final OracleDiscoveryService service;
    private final AuthorizationService authorization;

    OracleDiscoveryController(
            OracleDiscoveryService service,
            AuthorizationService authorization) {
        this.service = service;
        this.authorization = authorization;
    }

    @GetMapping("/schemas")
    List<String> listSchemas(
            @PathVariable UUID projectUuid,
            @PathVariable UUID connectionUuid) {
        authorization.requireProjectPermission(projectUuid, DISCOVERY_WRITE);
        return service.listSchemas(connectionUuid);
    }

    @PostMapping("/physical-schemas/{physicalSchemaUuid}/discover")
    DiscoveryView discover(
            @PathVariable UUID projectUuid,
            @PathVariable UUID connectionUuid,
            @PathVariable UUID physicalSchemaUuid,
            @Valid @RequestBody(required = false) DiscoveryRequest request) {
        authorization.requireProjectPermission(projectUuid, DISCOVERY_WRITE);
        DiscoveryRequest safeRequest = request == null
                ? new DiscoveryRequest(null, 100)
                : request;
        int limit = safeRequest.limit() == null ? 100 : safeRequest.limit();
        DiscoveryResult result = service.discover(
                connectionUuid,
                physicalSchemaUuid,
                safeRequest.tableName(),
                limit);
        return DiscoveryView.from(connectionUuid, physicalSchemaUuid, result);
    }

    record DiscoveryRequest(
            @Pattern(regexp = "[A-Za-z][A-Za-z0-9_$#]{0,127}") String tableName,
            @Min(1) @Max(200) Integer limit) {
    }

    record DiscoveryView(
            UUID connectionUuid,
            UUID physicalSchemaUuid,
            String owner,
            OffsetDateTime discoveredAt,
            boolean truncated,
            List<TableView> tables) {

        static DiscoveryView from(
                UUID connectionUuid,
                UUID physicalSchemaUuid,
                DiscoveryResult result) {
            return new DiscoveryView(
                    connectionUuid,
                    physicalSchemaUuid,
                    result.owner(),
                    result.discoveredAt(),
                    result.truncated(),
                    result.tables().stream().map(table -> TableView.from(result.technology(), table)).toList());
        }
    }

    record TableView(
            String owner,
            String name,
            String type,
            List<ColumnView> columns,
            List<ConstraintView> constraints) {

        static TableView from(String technology, TableMetadata table) {
            return new TableView(
                    table.owner(),
                    table.name(),
                    table.type(),
                    table.columns().stream().map(column -> ColumnView.from(technology, column)).toList(),
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

        static ColumnView from(String technology, ColumnMetadata column) {
            OracleColumnCapability.Classification capability = "POSTGRESQL".equals(technology)
                    ? tr.com.innova.akis.postgres.PostgresColumnCapability.classify(column.producerType(), column.precision(), column.scale())
                    : OracleColumnCapability.classify(
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
