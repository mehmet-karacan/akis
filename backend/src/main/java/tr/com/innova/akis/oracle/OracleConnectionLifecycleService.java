package tr.com.innova.akis.oracle;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import tr.com.innova.akis.metadata.ApiException;
import tr.com.innova.akis.oracle.OracleConnectionLifecycleModels.LifecycleRow;
import tr.com.innova.akis.oracle.OracleConnectionLifecycleModels.TestAttemptRow;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.ConnectionProbe;

@Service
final class OracleConnectionLifecycleService {

    private final OracleDiscoveryService discoveryService;
    private final OracleConnectionLifecycleRepository repository;
    private final TransactionTemplate transactions;

    OracleConnectionLifecycleService(
            OracleDiscoveryService discoveryService,
            OracleConnectionLifecycleRepository repository,
            TransactionTemplate transactions) {
        this.discoveryService = discoveryService;
        this.repository = repository;
        this.transactions = transactions;
    }

    TestAttemptRow test(
            UUID projectUuid, UUID connectionUuid, UUID connectionVersionUuid) {
        requireLifecycle(projectUuid, connectionUuid, connectionVersionUuid);
        OffsetDateTime startedAt = OffsetDateTime.now(ZoneOffset.UTC);
        try {
            ConnectionProbe probe = discoveryService.testConnection(
                    projectUuid, connectionUuid, connectionVersionUuid);
            OffsetDateTime completedAt = OffsetDateTime.now(ZoneOffset.UTC);
            TestAttemptRow attempt = transactions.execute(status -> recordSuccessful(
                    projectUuid, connectionUuid, connectionVersionUuid, probe,
                    startedAt, completedAt));
            if (attempt == null) {
                throw new IllegalStateException("Oracle connection test transaction returned no result.");
            }
            if ("TARGET_MISMATCH".equals(attempt.outcome())) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "ORACLE_TARGET_MISMATCH",
                        "Oracle bağlantısı sabitlenmiş veritabanı hedefiyle eşleşmiyor.");
            }
            return attempt;
        }
        catch (ApiException exception) {
            if ("ORACLE_TARGET_MISMATCH".equals(exception.code())) {
                throw exception;
            }
            OffsetDateTime completedAt = OffsetDateTime.now(ZoneOffset.UTC);
            transactions.executeWithoutResult(status -> recordFailed(
                    projectUuid, connectionUuid, connectionVersionUuid,
                    exception.code(), startedAt, completedAt));
            throw exception;
        }
        catch (RuntimeException exception) {
            OffsetDateTime completedAt = OffsetDateTime.now(ZoneOffset.UTC);
            transactions.executeWithoutResult(status -> recordFailed(
                    projectUuid, connectionUuid, connectionVersionUuid,
                    "ORACLE_TEST_FAILED", startedAt, completedAt));
            throw new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    "ORACLE_TEST_FAILED",
                    "Oracle bağlantı testi tamamlanamadı.");
        }
    }

    List<TestAttemptRow> listTests(
            UUID projectUuid,
            UUID connectionUuid,
            UUID connectionVersionUuid,
            int limit) {
        requireLifecycle(projectUuid, connectionUuid, connectionVersionUuid);
        if (limit < 1 || limit > 100) {
            throw validation("Test history limit must be between 1 and 100.");
        }
        return repository.listAttempts(
                projectUuid, connectionUuid, connectionVersionUuid, limit);
    }

    LifecycleRow lifecycle(
            UUID projectUuid, UUID connectionUuid, UUID connectionVersionUuid) {
        return requireLifecycle(projectUuid, connectionUuid, connectionVersionUuid);
    }

    LifecycleRow activate(
            UUID projectUuid,
            UUID connectionUuid,
            UUID connectionVersionUuid,
            UUID testUuid,
            long expectedStateVersion) {
        if (testUuid == null || expectedStateVersion < 1) {
            throw validation("A successful test and lifecycle version are required.");
        }
        try {
            LifecycleRow row = transactions.execute(status -> {
                LifecycleRow current = lockLifecycle(
                        projectUuid, connectionUuid, connectionVersionUuid);
                if ("ACTIVE".equals(current.status())
                        && testUuid.equals(current.latestSuccessfulTestUuid())
                        && current.stateVersion() == expectedStateVersion + 1) {
                    return current;
                }
                return repository.activate(
                        projectUuid, connectionUuid, connectionVersionUuid,
                        testUuid, expectedStateVersion);
            });
            if (row == null) {
                throw new IllegalStateException("Activation transaction returned no result.");
            }
            return row;
        }
        catch (OracleConnectionLifecycleRepository.LifecycleConflictException exception) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "CONNECTION_VERSION_STATE_CONFLICT",
                    "Bağlantı sürümü yalnız güncel başarılı test kanıtıyla aktive edilebilir.");
        }
        catch (OracleConnectionLifecycleRepository.LifecycleNotFoundException exception) {
            throw notFound();
        }
    }

    private TestAttemptRow recordSuccessful(
            UUID projectUuid,
            UUID connectionUuid,
            UUID connectionVersionUuid,
            ConnectionProbe probe,
            OffsetDateTime startedAt,
            OffsetDateTime completedAt) {
        LifecycleRow lifecycle = lockLifecycle(projectUuid, connectionUuid, connectionVersionUuid);
        boolean matches = lifecycle.targetFingerprint() == null
                || constantTimeEquals(lifecycle.targetFingerprint(), probe.targetFingerprint());
        String outcome = matches ? "PASSED" : "TARGET_MISMATCH";
        UUID testUuid = UUID.randomUUID();
        TestAttemptRow attempt = repository.insertSuccessfulAttempt(
                projectUuid, connectionUuid, connectionVersionUuid, testUuid,
                repository.nextAttemptNumber(projectUuid, connectionVersionUuid),
                outcome, probe, startedAt, completedAt, durationMillis(startedAt, completedAt));
        if (matches) {
            repository.markTested(
                    projectUuid, connectionVersionUuid, testUuid, probe, completedAt);
        }
        return attempt;
    }

    private void recordFailed(
            UUID projectUuid,
            UUID connectionUuid,
            UUID connectionVersionUuid,
            String errorCode,
            OffsetDateTime startedAt,
            OffsetDateTime completedAt) {
        lockLifecycle(projectUuid, connectionUuid, connectionVersionUuid);
        repository.insertFailedAttempt(
                projectUuid, connectionUuid, connectionVersionUuid, UUID.randomUUID(),
                repository.nextAttemptNumber(projectUuid, connectionVersionUuid),
                errorCode, startedAt, completedAt, durationMillis(startedAt, completedAt));
    }

    private LifecycleRow requireLifecycle(
            UUID projectUuid, UUID connectionUuid, UUID connectionVersionUuid) {
        return repository.findLifecycle(projectUuid, connectionUuid, connectionVersionUuid)
                .orElseThrow(this::notFound);
    }

    private LifecycleRow lockLifecycle(
            UUID projectUuid, UUID connectionUuid, UUID connectionVersionUuid) {
        try {
            return repository.lockLifecycle(projectUuid, connectionUuid, connectionVersionUuid);
        }
        catch (OracleConnectionLifecycleRepository.LifecycleNotFoundException exception) {
            throw notFound();
        }
    }

    private boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII));
    }

    private long durationMillis(OffsetDateTime start, OffsetDateTime end) {
        return Math.max(0L, Duration.between(start, end).toMillis());
    }

    private ApiException notFound() {
        return new ApiException(
                HttpStatus.NOT_FOUND, "NOT_FOUND", "Oracle bağlantı sürümü bulunamadı.");
    }

    private ApiException validation(String message) {
        return new ApiException(
                HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED", message);
    }
}
