package tr.com.innova.akis.knowledge;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/** Explicit lifecycle boundary; guarded runtime connections must use their owning session. */
public interface JdbcTransactionBoundary {
    void commit() throws SQLException;
    void rollback() throws SQLException;

    static JdbcTransactionBoundary direct(Connection connection) {
        Objects.requireNonNull(connection);
        return new JdbcTransactionBoundary() {
            public void commit() throws SQLException { connection.commit(); }
            public void rollback() throws SQLException { connection.rollback(); }
        };
    }
}
