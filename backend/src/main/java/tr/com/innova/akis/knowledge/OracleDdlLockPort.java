package tr.com.innova.akis.knowledge;

import java.sql.Connection;
import java.sql.SQLException;

interface OracleDdlLockPort {
    Lease acquire(Connection connection, String canonicalObjectName, int timeoutSeconds)
            throws SQLException;

    interface Lease extends AutoCloseable {
        @Override void close() throws SQLException;
    }
}
