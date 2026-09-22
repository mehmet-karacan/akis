package tr.com.innova.akis.knowledge;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.zip.CRC32;

/**
 * Session-level advisory lock guarding PostgreSQL work-table DDL. Session scope (not transaction scope) keeps the lease
 * semantics of the Oracle DBMS_LOCK guard: the lock survives the commit that makes the DDL visible and is released explicitly.
 */
final class JdbcPostgresDdlLock implements OracleDdlLockPort {
    @Override
    public Lease acquire(Connection connection, String canonicalObjectName, int timeoutSeconds) throws SQLException {
        if (connection == null || canonicalObjectName == null || canonicalObjectName.isBlank() || timeoutSeconds < 1 || timeoutSeconds > 3600) {
            throw new IllegalArgumentException("DDL lock contract is invalid.");
        }
        long key = lockKey(canonicalObjectName);
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_lock(?)")) {
            statement.setLong(1, key);
            statement.setQueryTimeout(timeoutSeconds);
            statement.execute();
        }
        return () -> release(connection, key);
    }

    private void release(Connection connection, long key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            statement.setLong(1, key);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !result.getBoolean(1)) throw new SQLException("PostgreSQL DDL advisory lock was not held at release.");
            }
        }
    }

    private static long lockKey(String value) {
        CRC32 crc = new CRC32();
        crc.update(value.getBytes(StandardCharsets.UTF_8));
        return ("AKIS_DDL".hashCode() & 0xffffffffL) << 32 | crc.getValue();
    }
}
