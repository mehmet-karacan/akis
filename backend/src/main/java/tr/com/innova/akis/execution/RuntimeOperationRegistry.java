package tr.com.innova.akis.execution;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Component;

/** Owner-scoped registry for runtime sessions that can be interrupted on stop. */
@Component
final class RuntimeOperationRegistry {
    private final ConcurrentMap<Key, ConcurrentMap<RuntimeOracleConnectionProvider.RuntimeOracleSession, Boolean>> active = new ConcurrentHashMap<>();

    void register(UUID runUuid, long generation, RuntimeOracleConnectionProvider.RuntimeOracleSession session) {
        active.computeIfAbsent(new Key(runUuid, generation), ignored -> new ConcurrentHashMap<>()).put(session, Boolean.TRUE);
    }

    void unregister(UUID runUuid, long generation, RuntimeOracleConnectionProvider.RuntimeOracleSession session) {
        Key key = new Key(runUuid, generation);
        var sessions = active.get(key);
        if (sessions != null) {
            sessions.remove(session);
            if (sessions.isEmpty()) active.remove(key, sessions);
        }
    }

    void cancel(UUID runUuid, long generation) {
        var sessions = active.get(new Key(runUuid, generation));
        if (sessions != null) sessions.keySet().forEach(RuntimeOracleConnectionProvider.RuntimeOracleSession::cancelActive);
    }

    private record Key(UUID runUuid, long generation) { }
}
