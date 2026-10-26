package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.sql.Connection;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import tr.com.innova.akis.execution.RuntimeOracleConnectionProvider.RuntimeOracleSession;
import tr.com.innova.akis.execution.RuntimeOracleConnectionProvider.SessionPurpose;

class RuntimeOperationRegistryTest {

    @Test
    void saturatedAbortPoolRetriesRejectedCancellationWithoutAnotherCancelCall() throws Exception {
        var executorField = RuntimeOperationRegistry.class.getDeclaredField("CANCELLATIONS");
        executorField.setAccessible(true);
        ThreadPoolExecutor executor = (ThreadPoolExecutor) executorField.get(null);
        RuntimeOperationRegistry operations = new RuntimeOperationRegistry();
        CountDownLatch workersStarted = new CountDownLatch(4);
        CountDownLatch releaseAborts = new CountDownLatch(1);
        CountDownLatch acceptedAbortsFinished = new CountDownLatch(132);
        AtomicBoolean nonDaemonWorker = new AtomicBoolean();
        Connection blocked = mock(Connection.class);
        doAnswer(invocation -> {
            if (!Thread.currentThread().isDaemon()) nonDaemonWorker.set(true);
            workersStarted.countDown();
            try {
                releaseAborts.await(10, TimeUnit.SECONDS);
            }
            finally {
                acceptedAbortsFinished.countDown();
            }
            return null;
        }).when(blocked).abort(any());
        var caller = Executors.newSingleThreadExecutor();
        try {
            for (int index = 0; index < 4; index++) {
                UUID run = UUID.randomUUID();
                operations.register(run, 4, session(blocked));
                caller.submit(() -> operations.cancel(run, 4)).get(2, TimeUnit.SECONDS);
            }
            assertTrue(workersStarted.await(2, TimeUnit.SECONDS));
            for (int index = 0; index < 128; index++) {
                UUID run = UUID.randomUUID();
                operations.register(run, 4, session(blocked));
                caller.submit(() -> operations.cancel(run, 4)).get(2, TimeUnit.SECONDS);
                assertEquals(4, executor.getLargestPoolSize(), "Blocked aborts must not create more workers");
                assertEquals(index + 1, executor.getQueue().size());
            }

            UUID rejectedRun = UUID.randomUUID();
            RuntimeOperationRegistry rejectedOperations = new RuntimeOperationRegistry();
            CountDownLatch retriedAbort = new CountDownLatch(1);
            Connection retry = mock(Connection.class);
            doAnswer(invocation -> { retriedAbort.countDown(); return null; }).when(retry).abort(any());
            rejectedOperations.register(rejectedRun, 4, session(retry));
            CountDownLatch unrelatedAbort = new CountDownLatch(1);
            Connection unrelated = mock(Connection.class);
            doAnswer(invocation -> { unrelatedAbort.countDown(); return null; }).when(unrelated).abort(any());
            rejectedOperations.register(rejectedRun, 5, session(unrelated));
            rejectedOperations.register(UUID.randomUUID(), 4, session(unrelated));

            // Rejection must neither run the abort on this caller nor escape as an exception.
            caller.submit(() -> rejectedOperations.cancel(rejectedRun, 4)).get(2, TimeUnit.SECONDS);
            assertEquals(4, executor.getLargestPoolSize());
            assertEquals(128, executor.getQueue().size());
            assertEquals(1, retriedAbort.getCount());

            releaseAborts.countDown();
            assertTrue(acceptedAbortsFinished.await(2, TimeUnit.SECONDS));
            assertTrue(retriedAbort.await(3, TimeUnit.SECONDS),
                    "Rejected submission must retry without another heartbeat or cancel call");
            assertFalse(unrelatedAbort.await(200, TimeUnit.MILLISECONDS));
            assertFalse(nonDaemonWorker.get());
        }
        finally {
            releaseAborts.countDown();
            caller.shutdownNow();
        }
    }

    @Test
    void slowAbortDoesNotHoldCallerOrOtherSessionsAndRepeatedCancelIsDeduplicated() throws Exception {
        RuntimeOperationRegistry operations = new RuntimeOperationRegistry();
        UUID run = UUID.randomUUID();
        CountDownLatch abortStarted = new CountDownLatch(1);
        CountDownLatch releaseAbort = new CountDownLatch(1);
        CountDownLatch fastAbort = new CountDownLatch(1);
        AtomicInteger aborts = new AtomicInteger();
        Connection slow = mock(Connection.class);
        doAnswer(invocation -> {
            aborts.incrementAndGet();
            abortStarted.countDown();
            releaseAbort.await(10, TimeUnit.SECONDS);
            return null;
        }).when(slow).abort(any());
        Connection fast = mock(Connection.class);
        doAnswer(invocation -> { fastAbort.countDown(); return null; }).when(fast).abort(any());
        RuntimeOracleSession slowSession = session(slow);
        operations.register(run, 4, slowSession);
        var caller = Executors.newSingleThreadExecutor();
        try {
            var cancellation = caller.submit(() -> operations.cancel(run, 4));
            assertTrue(abortStarted.await(2, TimeUnit.SECONDS));
            cancellation.get(2, TimeUnit.SECONDS);
            assertEquals(1, releaseAbort.getCount(), "Cancel must return while abort is blocked");
            operations.register(run, 4, slowSession);
            operations.register(run, 4, session(fast));
            operations.cancel(run, 4);
            assertTrue(fastAbort.await(2, TimeUnit.SECONDS), "A slow abort must not delay another session");
            assertEquals(1, aborts.get(), "Repeated cancellation must not queue duplicate aborts");
        }
        finally {
            releaseAbort.countDown();
            caller.shutdownNow();
        }
    }

    @Test
    void cancellationOnlyInterruptsRegisteredSessionsOfExactRunAndGeneration() throws Exception {
        RuntimeOperationRegistry operations = new RuntimeOperationRegistry();
        UUID run = UUID.randomUUID();
        CountDownLatch matchingAbort = new CountDownLatch(1);
        CountDownLatch unrelatedAbort = new CountDownLatch(1);
        Connection matching = mock(Connection.class);
        doAnswer(invocation -> { matchingAbort.countDown(); return null; }).when(matching).abort(any());
        Connection unrelated = mock(Connection.class);
        doAnswer(invocation -> { unrelatedAbort.countDown(); return null; }).when(unrelated).abort(any());
        operations.register(run, 4, session(matching));
        operations.register(run, 5, session(unrelated));
        operations.register(UUID.randomUUID(), 4, session(unrelated));
        RuntimeOracleSession removed = session(unrelated);
        operations.register(run, 4, removed);
        operations.unregister(run, 4, removed);

        operations.cancel(run, 4);

        assertTrue(matchingAbort.await(2, TimeUnit.SECONDS));
        assertFalse(unrelatedAbort.await(200, TimeUnit.MILLISECONDS));
    }

    private RuntimeOracleSession session(Connection connection) throws Exception {
        var constructor = RuntimeOracleSession.class.getDeclaredConstructor(
                Connection.class, SessionPurpose.class, int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(connection, SessionPurpose.SOURCE_READ, 30);
    }
}
