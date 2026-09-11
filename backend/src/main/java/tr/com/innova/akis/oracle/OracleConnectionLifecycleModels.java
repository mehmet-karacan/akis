package tr.com.innova.akis.oracle;

import java.time.OffsetDateTime;
import java.util.UUID;

final class OracleConnectionLifecycleModels {

    private OracleConnectionLifecycleModels() {
    }

    record LifecycleRow(
            UUID connectionVersionUuid,
            String status,
            long stateVersion,
            Integer targetIdentityVersion,
            String targetFingerprint,
            UUID latestSuccessfulTestUuid,
            OffsetDateTime testedAt,
            OffsetDateTime activatedAt) {
    }

    record TestAttemptRow(
            UUID uuid,
            UUID connectionVersionUuid,
            int attemptNumber,
            String outcome,
            String errorCode,
            String databaseProduct,
            String databaseVersion,
            Integer databaseMajorVersion,
            Integer databaseMinorVersion,
            String driverName,
            String driverVersion,
            Integer targetIdentityVersion,
            String targetFingerprint,
            OffsetDateTime startedAt,
            OffsetDateTime completedAt,
            long durationMs) {
    }
}
