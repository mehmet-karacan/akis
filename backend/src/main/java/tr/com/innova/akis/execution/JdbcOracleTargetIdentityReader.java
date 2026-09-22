package tr.com.innova.akis.execution;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

import tr.com.innova.akis.execution.OracleTargetIdentityV1.CanonicalTargetIdentity;
import tr.com.innova.akis.execution.OracleTargetIdentityV1.ValidatedTargetObject;
import tr.com.innova.akis.execution.OracleTargetIdentityV1.VerifiedDatabaseIdentity;

/** Reads a target identity from Oracle without changing session or database state. */
final class JdbcOracleTargetIdentityReader implements TargetIdentityPort {

    private static final String DATABASE_IDENTITY_SQL = """
            SELECT SYS_CONTEXT('USERENV', 'DB_UNIQUE_NAME') AS DB_UNIQUE_NAME,
                   SYS_CONTEXT('USERENV', 'CON_NAME') AS CON_NAME
              FROM SYS.DUAL
            """;
    private static final String OBJECT_IDENTITY_SQL = """
            SELECT COUNT(*) AS OBJECT_COUNT
              FROM ALL_OBJECTS
             WHERE OWNER = ?
               AND OBJECT_TYPE = 'TABLE'
               AND OBJECT_NAME = ?
               AND SUBOBJECT_NAME IS NULL
            """;

    private final OracleTargetIdentityV1 canonicalizer;

    JdbcOracleTargetIdentityReader() {
        this(new OracleTargetIdentityV1());
    }

    JdbcOracleTargetIdentityReader(OracleTargetIdentityV1 canonicalizer) {
        this.canonicalizer = Objects.requireNonNull(canonicalizer, "Canonicalizer is required.");
    }

    @Override
    public CanonicalTargetIdentity read(
            Connection connection,
            String owner,
            String objectType,
            String objectName) {
        Objects.requireNonNull(connection, "Oracle connection is required.");

        // Validate the caller-controlled identifiers before issuing any SQL.
        ValidatedTargetObject target = canonicalizer.validateTarget(owner, objectType, objectName);
        try {
            VerifiedDatabaseIdentity database = readDatabaseIdentity(connection);
            requireExactlyOneObject(connection, target.owner(), target.objectName());
            return canonicalizer.canonicalize(
                    database,
                    target.owner(),
                    target.objectType(),
                    target.objectName());
        }
        catch (SQLException exception) {
            throw new OracleTargetIdentityException(
                    "Oracle target identity could not be verified.", exception);
        }
    }

    private VerifiedDatabaseIdentity readDatabaseIdentity(Connection connection)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(DATABASE_IDENTITY_SQL);
                ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                throw verificationFailure("Oracle database identity query returned no row.");
            }
            String databaseUniqueName = resultSet.getString("DB_UNIQUE_NAME");
            String containerName = resultSet.getString("CON_NAME");
            if (resultSet.next()) {
                throw verificationFailure("Oracle database identity query was ambiguous.");
            }
            return new VerifiedDatabaseIdentity(databaseUniqueName, containerName);
        }
    }

    private void requireExactlyOneObject(
            Connection connection, String owner, String objectName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(OBJECT_IDENTITY_SQL)) {
            statement.setString(1, owner);
            statement.setString(2, objectName);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw verificationFailure("Oracle target object query returned no row.");
                }
                int objectCount = resultSet.getInt("OBJECT_COUNT");
                if (resultSet.wasNull() || objectCount != 1 || resultSet.next()) {
                    throw verificationFailure(
                            "Oracle target object is missing or ambiguous for the active container.");
                }
            }
        }
    }

    private OracleTargetIdentityException verificationFailure(String message) {
        return new OracleTargetIdentityException(message);
    }
}
