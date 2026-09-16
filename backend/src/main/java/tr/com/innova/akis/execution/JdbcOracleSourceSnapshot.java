package tr.com.innova.akis.execution;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/** Captures an SCN from the source database itself; never reuses target metadata. */
final class JdbcOracleSourceSnapshot implements SourceSnapshotPort {

    private final Connection connection;
    private final String databaseFingerprint;

    JdbcOracleSourceSnapshot(Connection connection, String databaseFingerprint) {
        this.connection = Objects.requireNonNull(connection);
        this.databaseFingerprint = databaseFingerprint;
    }

    @Override
    public SourceSnapshot capture() {
        try (var statement = connection.prepareStatement(
                "SELECT DBMS_FLASHBACK.GET_SYSTEM_CHANGE_NUMBER FROM DUAL");
                var result = statement.executeQuery()) {
            if (!result.next()) throw new SQLException("Oracle source SCN was not returned.");
            BigDecimal value = result.getBigDecimal(1);
            if (value == null || value.scale() > 0) {
                throw new SQLException("Oracle source SCN is not an integer.");
            }
            BigInteger scn = value.toBigIntegerExact();
            return new SourceSnapshot(scn, databaseFingerprint);
        }
        catch (SQLException | ArithmeticException exception) {
            throw new SourceSnapshotUnavailableException(exception);
        }
    }
}

final class SourceSnapshotUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    SourceSnapshotUnavailableException(Throwable cause) {
        super("SOURCE_SNAPSHOT_UNAVAILABLE", cause);
    }
}
