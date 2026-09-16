package tr.com.innova.akis.knowledge;

import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static tr.com.innova.akis.knowledge.WorkObjectLifecycle.State;

class OracleKmRuntimeTest {
    private final Connection source = mock(Connection.class), control = mock(Connection.class), data = mock(Connection.class);
    private final OracleWorkTableManager tables = mock(OracleWorkTableManager.class);
    private final WorkObjectStore store = mock(WorkObjectStore.class);
    private final JdbcStagingTransfer transfer = mock(JdbcStagingTransfer.class);
    private final JdbcWorkQualityChecks checks = mock(JdbcWorkQualityChecks.class);
    private final OracleKmRuntime.Guard guard = mock(OracleKmRuntime.Guard.class);
    private final OracleKmRuntime.Publisher publisher = mock(OracleKmRuntime.Publisher.class);
    private final JdbcTransactionBoundary transaction = mock(JdbcTransactionBoundary.class);
    private final AkisKmInterpreter.Modules modules = new AkisKmInterpreter.Modules(
            AkisKmLanguage.example(AkisKmLanguage.Kind.LKM), AkisKmLanguage.example(AkisKmLanguage.Kind.CKM),
            AkisKmLanguage.example(AkisKmLanguage.Kind.IKM));
    private final WorkObjectStore.Owner owner = new WorkObjectStore.Owner(UUID.randomUUID(), UUID.randomUUID(), 1, "worker");
    private final JdbcStagingTransfer.Table work = new JdbcStagingTransfer.Table("WORK", "AKIS_C_TEST");
    private final OracleWorkTableManager.Created created = new OracleWorkTableManager.Created(
            UUID.randomUUID(), "a".repeat(64), work, 123L, "b".repeat(64));
    private final JdbcStagingTransfer.Result seal = new JdbcStagingTransfer.Result(1201, 20000, "c".repeat(64));
    private OracleKmRuntime runtime() throws SQLException {
        var contract = new OracleKmRuntime.Contract(owner, AkisKmInterpreter.compile(modules), "a".repeat(64), "DATA",
                new JdbcStagingTransfer.Table("SRC", "ITEMS"), work,
                List.of(new OracleWorkTableManager.Column("ID", "NUMBER")),
                List.of(new JdbcStagingTransfer.Column("ID", "ID", JdbcStagingTransfer.Type.NUMBER)),
                new StagedMappingDefinition.Options(500, 500, 2000, 100000, false),
                new JdbcWorkQualityChecks.Contract(List.of("ID"), List.of()), 30,new WorkObjectStore.WorkArea(UUID.randomUUID(),1));
        when(tables.create(eq(control), eq(owner), anyString(), eq(work), anyList(), eq(30), any(),any())).thenReturn(created);
        when(transfer.transfer(eq(source), eq(data), any(), eq(work), anyList(), any(), eq(30), any(), eq(transaction))).thenReturn(seal);
        var statement = mock(PreparedStatement.class);
        var result = mock(ResultSet.class);
        when(data.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true, false); when(result.getLong(1)).thenReturn(1201L);
        when(publisher.publish(created, seal)).thenReturn(new OracleKmRuntime.PublishResult(OracleKmRuntime.PublishOutcome.COMMITTED, 1201));
        return new OracleKmRuntime(contract, source, control, data, transaction, tables, store, transfer, checks, guard, publisher);
    }
    @Test void interpreterRunsLoadingQualityAndPublicationInOrder() throws Exception {
        var results = AkisKmInterpreter.execute(modules, runtime());
        assertEquals(5, results.size()); assertEquals(1201, results.getLast().affectedRows());
        var order = inOrder(guard, tables, store, transfer, checks, publisher);
        order.verify(guard).preflight();
        order.verify(tables).create(eq(control), eq(owner), anyString(), eq(work), anyList(), eq(30), any(),any());
        order.verify(store).transition(owner, created.uuid(), State.READY, State.LOADING, null, null);
        order.verify(transfer).transfer(eq(source), eq(data), any(), eq(work), anyList(), any(), eq(30), any(), eq(transaction));
        order.verify(store).transition(owner, created.uuid(), State.LOADING, State.SEALED, null, seal);
        order.verify(checks).verify(eq(data), eq(work), any(), eq(JdbcWorkQualityChecks.Rule.NOT_NULL), eq(30), any());
        order.verify(publisher).publish(created, seal);
        order.verify(store).transition(owner, created.uuid(), State.SEALED, State.CONSUMED, null, null);
    }
    @Test void failedPreflightCannotCreateWork() throws Exception {
        var runtime = runtime(); doThrow(new IllegalStateException()).when(guard).preflight();
        assertThrows(IllegalStateException.class, () -> AkisKmInterpreter.execute(modules, runtime));
        verify(tables, never()).create(any(), any(), anyString(), any(), anyList(), anyInt(), any(),any());
        verifyNoInteractions(publisher);
    }
    @Test void transferFailureNeverSealsOrPublishes() throws Exception {
        var runtime = runtime();
        when(transfer.transfer(eq(source), eq(data), any(), eq(work), anyList(), any(), eq(30), any(), eq(transaction)))
                .thenThrow(new JdbcStagingTransfer.TransferFailure("quota", false));
        assertThrows(JdbcStagingTransfer.TransferFailure.class, () -> AkisKmInterpreter.execute(modules, runtime));
        verify(store).transition(owner, created.uuid(), State.LOADING, State.REVIEW_REQUIRED, null, null);
        verifyNoInteractions(publisher, checks);
    }
    @Test void qualityFailurePreservesWorkAndNeverPublishes() throws Exception {
        var runtime = runtime();
        doThrow(new JdbcWorkQualityChecks.CheckFailure(JdbcWorkQualityChecks.Rule.NOT_NULL)).when(checks)
                .verify(eq(data), eq(work), any(), any(), anyInt(), any());
        assertThrows(JdbcWorkQualityChecks.CheckFailure.class, () -> AkisKmInterpreter.execute(modules, runtime));
        verify(store).transition(owner, created.uuid(), State.SEALED, State.REVIEW_REQUIRED, null, null);
        verifyNoInteractions(publisher);
    }
    @Test void unknownPublicationCannotBeRetriedOrMarkedConsumed() throws Exception {
        var runtime = runtime();
        when(publisher.publish(created, seal)).thenReturn(new OracleKmRuntime.PublishResult(OracleKmRuntime.PublishOutcome.UNKNOWN, 0));
        var failure = assertThrows(OracleKmRuntime.PublishFailure.class, () -> AkisKmInterpreter.execute(modules, runtime));
        assertEquals(OracleKmRuntime.PublishOutcome.UNKNOWN, failure.outcome());
        assertThrows(IllegalStateException.class, () -> runtime.atomicReplace("WORK_SOURCE_1"));
        verify(publisher, times(1)).publish(any(), any());
        verify(store, never()).transition(any(), any(), eq(State.SEALED), eq(State.CONSUMED), any(), any());
    }
    @Test void controlPlaneFailureAfterCommitRequiresReconciliation() throws Exception {
        var runtime = runtime();
        doThrow(new IllegalStateException()).when(store).transition(owner, created.uuid(), State.SEALED, State.CONSUMED, null, null);
        var failure = assertThrows(OracleKmRuntime.PublishFailure.class, () -> AkisKmInterpreter.execute(modules, runtime));
        assertEquals(OracleKmRuntime.PublishOutcome.UNKNOWN, failure.outcome());
        verify(publisher, times(1)).publish(created, seal);
    }
}
