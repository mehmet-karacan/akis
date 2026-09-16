package tr.com.innova.akis.knowledge;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;

/** Session-scoped DBMS_LOCK guard for Oracle DDL, which implicitly commits row locks. */
final class JdbcOracleDdlLock implements OracleDdlLockPort {

    @Override
    public Lease acquire(Connection connection, String canonicalObjectName, int timeoutSeconds)
            throws SQLException {
        if (connection == null || canonicalObjectName == null || canonicalObjectName.isBlank()
                || timeoutSeconds < 1 || timeoutSeconds > 3600) {
            throw new IllegalArgumentException("DDL lock contract is invalid.");
        }
        int lockId = lockId(canonicalObjectName);
        int result;
        try (CallableStatement statement = connection.prepareCall(
                "BEGIN ? := DBMS_LOCK.REQUEST(?, 6, ?, FALSE); END;")) {
            statement.registerOutParameter(1, Types.INTEGER);
            statement.setInt(2, lockId);
            statement.setInt(3, timeoutSeconds);
            statement.execute();
            result = statement.getInt(1);
        }
        if (result != 0 && result != 4) {
            throw new SQLException("Oracle DDL session lock could not be acquired; result=" + result);
        }
        return () -> release(connection, lockId);
    }

    private void release(Connection connection, int lockId) throws SQLException {
        int result;
        try (CallableStatement statement = connection.prepareCall(
                "BEGIN ? := DBMS_LOCK.RELEASE(?); END;")) {
            statement.registerOutParameter(1, Types.INTEGER);
            statement.setInt(2, lockId);
            statement.execute();
            result = statement.getInt(1);
        }
        if (result != 0 && result != 4) {
            throw new SQLException("Oracle DDL session lock could not be released; result=" + result);
        }
    }

    private int lockId(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(hash).getInt() & 0x3fffffff;
        }
        catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
