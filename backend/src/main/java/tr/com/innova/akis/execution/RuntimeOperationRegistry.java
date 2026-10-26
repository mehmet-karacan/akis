package tr.com.innova.akis.execution;

import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

/** Owner-scoped registry for runtime sessions that can be interrupted on stop. */
@Component
final class RuntimeOperationRegistry {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(RuntimeOperationRegistry.class);

    // Bound both workers and queued aborts: a driver may hang indefinitely inside abort.
    private static final ThreadPoolExecutor CANCELLATIONS = new ThreadPoolExecutor(
            4, 4, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(128),
            Thread.ofPlatform().daemon().name("akis-session-cancel-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());

    private static final ConcurrentMap<DeferredCancellation, ConcurrentMap<RuntimeOracleConnectionProvider.RuntimeOracleSession, Boolean>> DEFERRED = new ConcurrentHashMap<>();
    // Exactly one periodic task; requests never add threads or tasks to this scheduler's queue.
    private static final ScheduledExecutorService RETRIES = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("akis-session-cancel-retry").factory());
    static {
        RETRIES.scheduleWithFixedDelay(RuntimeOperationRegistry::retryDeferred, 100, 100, TimeUnit.MILLISECONDS);
    }

    private final ConcurrentMap<Key, ConcurrentMap<RuntimeOracleConnectionProvider.RuntimeOracleSession, Boolean>> active = new ConcurrentHashMap<>();

    void register(UUID runUuid, long generation, RuntimeOracleConnectionProvider.RuntimeOracleSession session) {
        active.computeIfAbsent(new Key(runUuid, generation), ignored -> new ConcurrentHashMap<>()).putIfAbsent(session, Boolean.FALSE);
    }

    void unregister(UUID runUuid, long generation, RuntimeOracleConnectionProvider.RuntimeOracleSession session) {
        Key key = new Key(runUuid, generation);
        var sessions = active.get(key);
        if (sessions != null) {
            sessions.remove(session);
            DEFERRED.remove(new DeferredCancellation(this, key, session), sessions);
            if (sessions.isEmpty()) active.remove(key, sessions);
        }
    }

    void cancel(UUID runUuid, long generation) {
        Key key = new Key(runUuid, generation);
        var sessions = active.get(key);
        if (sessions != null) sessions.keySet().forEach(session ->
                submit(new DeferredCancellation(this, key, session), sessions, true));
    }

    private static void submit(DeferredCancellation request,
            ConcurrentMap<RuntimeOracleConnectionProvider.RuntimeOracleSession, Boolean> sessions,
            boolean logRejection) {
        if (sessions.replace(request.session(), Boolean.FALSE, Boolean.TRUE)) {
            try {
                CANCELLATIONS.execute(request.session()::cancelActive);
                DEFERRED.remove(request, sessions);
            }
            catch (RejectedExecutionException ignored) {
                sessions.replace(request.session(), Boolean.TRUE, Boolean.FALSE);
                DEFERRED.put(request, sessions);
                if (logRejection) LOG.warn(
                        "Runtime session cancellation deferred for run {} generation {}: abort submission rejected.",
                        request.scope().runUuid(), request.scope().generation());
            }
        }
    }

    private static void retryDeferred() {
        DEFERRED.forEach((request, sessions) -> {
            if (request.registry().active.get(request.scope()) != sessions
                    || !sessions.containsKey(request.session())) {
                DEFERRED.remove(request, sessions);
            }
            else submit(request, sessions, false);
        });
    }

    private record Key(UUID runUuid, long generation) { }
    private record DeferredCancellation(RuntimeOperationRegistry registry, Key scope,
            RuntimeOracleConnectionProvider.RuntimeOracleSession session) { }
}
