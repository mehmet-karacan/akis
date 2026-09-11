package tr.com.innova.akis.execution;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Performs dictionary-only privilege checks; it never probes by executing DDL/DML. */
final class JdbcOracleTargetPrivilegeReader {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(JdbcOracleTargetPrivilegeReader.class);

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Z][A-Z0-9_$#]{0,127}");
    private static final String SQL = """
            SELECT SYS_CONTEXT('USERENV', 'CURRENT_USER') AS SESSION_USER_NAME,
                   (SELECT COUNT(*)
                         FROM ALL_TAB_PRIVS p
                        WHERE p.TABLE_SCHEMA = 'SYS'
                          AND p.TABLE_NAME = 'DBMS_STATS'
                          AND p.PRIVILEGE = 'EXECUTE'
                          AND (p.GRANTEE IN (
                                  SYS_CONTEXT('USERENV', 'CURRENT_USER'), 'PUBLIC')
                               OR p.GRANTEE IN (SELECT ROLE FROM SESSION_ROLES))
                   ) AS DBMS_STATS_GRANT_COUNT
              FROM SYS.DUAL
            """;

    Observation read(Connection connection, String owner) {
        if (connection == null || owner == null || !IDENTIFIER.matcher(owner).matches()) {
            throw new IllegalArgumentException("Target privilege contract is invalid.");
        }
        try (PreparedStatement statement = connection.prepareStatement(SQL);
                ResultSet rows = statement.executeQuery()) {
            if (!rows.next()) {
                throw new SQLException("Privilege query returned no row.");
            }
            String currentUser = rows.getString("SESSION_USER_NAME");
            boolean dbmsStats = rows.getInt("DBMS_STATS_GRANT_COUNT") > 0
                    && !rows.wasNull();
            if (rows.next() || currentUser == null) {
                throw new SQLException("Privilege query was ambiguous.");
            }
            boolean ownsTarget = owner.equals(currentUser.strip().toUpperCase(Locale.ROOT));
            return new Observation(
                    currentUser.strip().toUpperCase(Locale.ROOT),
                    ownsTarget,
                    ownsTarget,
                    ownsTarget,
                    dbmsStats);
        }
        catch (SQLException exception) {
            LOGGER.warn("Oracle target privilege metadata query failed; sqlState={}, vendorCode={}",
                    exception.getSQLState(), exception.getErrorCode());
            throw new OracleTargetPrivilegeException(
                    exception.getSQLState(), exception.getErrorCode());
        }
    }

    record Observation(
            String currentUser,
            boolean ownsTarget,
            boolean canTruncate,
            boolean canInsert,
            boolean canExecuteDbmsStats) {

        boolean allRequired() {
            return ownsTarget && canTruncate && canInsert && canExecuteDbmsStats;
        }
    }
}

final class OracleTargetPrivilegeException extends RuntimeException {

    private final String sqlState;
    private final int vendorCode;

    OracleTargetPrivilegeException(String sqlState, int vendorCode) {
        super("Oracle target privileges could not be verified.");
        this.sqlState = sqlState;
        this.vendorCode = vendorCode;
    }

    String sqlState() {
        return sqlState;
    }

    int vendorCode() {
        return vendorCode;
    }
}
