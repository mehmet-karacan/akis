package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tr.com.innova.akis.execution.ExecutionModels.Actor;
import tr.com.innova.akis.execution.ExecutionModels.PublicationContext;
import tr.com.innova.akis.execution.ExecutionModels.RunRow;
import tr.com.innova.akis.execution.ExecutionRecoveryPolicy.RecoveryAction;
import tr.com.innova.akis.execution.ExecutionRecoveryStore.RecoveryApplication;
import tr.com.innova.akis.metadata.ApiException;

class ExecutionRecoveryServiceTest {

    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID RUN = UUID.randomUUID();
    private static final UUID PUBLICATION = UUID.randomUUID();
    private static final Actor ACTOR = new Actor(9, UUID.randomUUID(), "operator");

    @Test
    void stalePreviewNeverCreatesANewAttempt() {
        Fixture fixture = new Fixture("DUSUK");
        RecoveryPlan preview = fixture.service.recoveryPlan(PROJECT, RUN);

        ApiException error = assertThrows(ApiException.class, () -> fixture.service.recover(
                PROJECT, RUN, "recovery-key-01", RecoveryAction.RESUME.name(),
                Long.toString(preview.expectedStateVersion() + 1), preview.planHash(), ACTOR));

        assertEquals("RECOVERY_PLAN_STALE", error.code());
        verify(fixture.recovery, never()).create(any(), any(), any(), any(), any(), any());
    }

    @Test
    void productionPermissionAndActivePublicationAreRecheckedAtApplyTime() {
        Fixture fixture = new Fixture("URETIM");
        RecoveryPlan preview = fixture.service.recoveryPlan(PROJECT, RUN);
        RunRow child = run(UUID.randomUUID());
        when(fixture.recovery.create(any(), eq(ACTOR), eq(RecoveryAction.RESUME),
                any(), any(), any())).thenReturn(new RecoveryApplication(
                        UUID.randomUUID(), child, "request", true));

        fixture.service.recover(PROJECT, RUN, "recovery-key-02",
                RecoveryAction.RESUME.name(),
                Long.toString(preview.expectedStateVersion()), preview.planHash(), ACTOR);

        verify(fixture.permissions).requireProductionRun(PROJECT);
        verify(fixture.recovery).create(any(), eq(ACTOR), eq(RecoveryAction.RESUME),
                any(), any(), any());
    }

    private static RunRow run(UUID runUuid) {
        return new RunRow(1, 2, 3, UUID.randomUUID(), runUuid, PUBLICATION, 1,
                "ILK", "BASARISIZ", "a".repeat(64), "b".repeat(64), 7,
                OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now(), null);
    }

    private static final class Fixture {
        private final ExecutionStore store = mock(ExecutionStore.class);
        private final ExecutionPermissionGate permissions = mock(ExecutionPermissionGate.class);
        private final ExecutionRecoveryStore recovery = mock(ExecutionRecoveryStore.class);
        private final ExecutionService service;

        private Fixture(String risk) {
            RunRow parent = run(RUN);
            when(store.find(PROJECT, RUN)).thenReturn(Optional.of(parent));
            when(store.lock(PROJECT, RUN)).thenReturn(Optional.of(parent));
            when(store.listSteps(PROJECT, RUN)).thenReturn(List.of());
            when(store.hasCompleteInputSnapshot(PROJECT, RUN)).thenReturn(true);
            when(store.lockPublication(PROJECT, PUBLICATION)).thenReturn(Optional.of(
                    new PublicationContext(1, 2, PUBLICATION, "AKTIF", risk,
                            "a".repeat(64), "b".repeat(64), null)));
            when(recovery.find(eq(PROJECT), eq(RUN), eq(ACTOR.id()), any()))
                    .thenReturn(Optional.empty());
            service = new ExecutionService(store, permissions,
                    new ExecutionFeatureFlags(true, false, true, false, true, false, true, false),
                    new RunStateMachine(), recovery);
        }
    }
}
