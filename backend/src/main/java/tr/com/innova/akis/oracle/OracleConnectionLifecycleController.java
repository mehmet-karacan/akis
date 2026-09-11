package tr.com.innova.akis.oracle;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import tr.com.innova.akis.oracle.OracleConnectionLifecycleModels.LifecycleRow;
import tr.com.innova.akis.oracle.OracleConnectionLifecycleModels.TestAttemptRow;
import tr.com.innova.akis.security.AuthorizationService;
import static tr.com.innova.akis.security.PermissionCodes.DISCOVERY_WRITE;
import static tr.com.innova.akis.security.PermissionCodes.TOPOLOGY_READ;
import static tr.com.innova.akis.security.PermissionCodes.TOPOLOGY_WRITE;

@RestController
@RequestMapping("/api/v2/projects/{projectUuid}/connections/{connectionUuid}/versions/{connectionVersionUuid}")
final class OracleConnectionLifecycleController {

    private final OracleConnectionLifecycleService service;
    private final AuthorizationService authorization;

    OracleConnectionLifecycleController(
            OracleConnectionLifecycleService service,
            AuthorizationService authorization) {
        this.service = service;
        this.authorization = authorization;
    }

    @PostMapping("/tests")
    ResponseEntity<TestAttemptView> test(
            @PathVariable UUID projectUuid,
            @PathVariable UUID connectionUuid,
            @PathVariable UUID connectionVersionUuid) {
        authorization.requireProjectPermission(projectUuid, DISCOVERY_WRITE);
        return ResponseEntity.status(HttpStatus.CREATED).body(TestAttemptView.from(service.test(
                projectUuid, connectionUuid, connectionVersionUuid)));
    }

    @GetMapping("/tests")
    List<TestAttemptView> tests(
            @PathVariable UUID projectUuid,
            @PathVariable UUID connectionUuid,
            @PathVariable UUID connectionVersionUuid,
            @RequestParam(defaultValue = "20") int limit) {
        authorization.requireProjectPermission(projectUuid, TOPOLOGY_READ);
        return service.listTests(
                        projectUuid, connectionUuid, connectionVersionUuid, limit)
                .stream().map(TestAttemptView::from).toList();
    }

    @GetMapping("/lifecycle")
    LifecycleView lifecycle(
            @PathVariable UUID projectUuid,
            @PathVariable UUID connectionUuid,
            @PathVariable UUID connectionVersionUuid) {
        authorization.requireProjectPermission(projectUuid, TOPOLOGY_READ);
        return LifecycleView.from(service.lifecycle(
                projectUuid, connectionUuid, connectionVersionUuid));
    }

    @PostMapping("/activate")
    LifecycleView activate(
            @PathVariable UUID projectUuid,
            @PathVariable UUID connectionUuid,
            @PathVariable UUID connectionVersionUuid,
            @Valid @RequestBody ActivateRequest request) {
        authorization.requireProjectPermission(projectUuid, TOPOLOGY_WRITE);
        return LifecycleView.from(service.activate(
                projectUuid, connectionUuid, connectionVersionUuid,
                request.testUuid(), request.expectedStateVersion()));
    }

    record ActivateRequest(
            @NotNull UUID testUuid,
            @Min(1) long expectedStateVersion) {
    }

    record LifecycleView(
            UUID connectionVersionUuid,
            String status,
            long stateVersion,
            Integer targetIdentityVersion,
            String targetFingerprint,
            UUID latestSuccessfulTestUuid,
            OffsetDateTime testedAt,
            OffsetDateTime activatedAt) {

        static LifecycleView from(LifecycleRow row) {
            return new LifecycleView(
                    row.connectionVersionUuid(), row.status(), row.stateVersion(),
                    row.targetIdentityVersion(), row.targetFingerprint(),
                    row.latestSuccessfulTestUuid(), row.testedAt(), row.activatedAt());
        }
    }

    record TestAttemptView(
            UUID uuid,
            UUID connectionVersionUuid,
            int attemptNumber,
            String outcome,
            String errorCode,
            ProbeView probe,
            Integer targetIdentityVersion,
            String targetFingerprint,
            OffsetDateTime startedAt,
            OffsetDateTime completedAt,
            long durationMs) {

        static TestAttemptView from(TestAttemptRow row) {
            ProbeView probe = row.databaseProduct() == null ? null : new ProbeView(
                    row.databaseProduct(), row.databaseVersion(),
                    row.databaseMajorVersion(), row.databaseMinorVersion(),
                    row.driverName(), row.driverVersion());
            return new TestAttemptView(
                    row.uuid(), row.connectionVersionUuid(), row.attemptNumber(),
                    row.outcome(), row.errorCode(), probe, row.targetIdentityVersion(),
                    row.targetFingerprint(), row.startedAt(), row.completedAt(), row.durationMs());
        }
    }

    record ProbeView(
            String databaseProduct,
            String databaseVersion,
            Integer databaseMajorVersion,
            Integer databaseMinorVersion,
            String driverName,
            String driverVersion) {
    }
}
