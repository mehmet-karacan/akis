package tr.com.innova.akis.execution;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import tr.com.innova.akis.execution.PostgresTargetIdentityV1.VerifiedRelation;
import tr.com.innova.akis.execution.TargetIdentityPort.CanonicalTargetIdentity;

/**
 * PostgreSQL adapter of {@link TargetIdentityPort}: reads the ledger installation uuid and the relation's catalog identity
 * on the given session. Requires the target ledger (akis_yayin_defteri) to be installed; without it there is no stable
 * installation identity and the target cannot be fenced anyway.
 */
final class JdbcPostgresTargetIdentityReader implements TargetIdentityPort {
    private static final String IDENTITY_SQL = """
            SELECT k.kurulum_uuid::text AS installation_uuid, current_database() AS database_name, d.oid AS database_oid,
                   n.nspname AS schema_name, n.oid AS schema_oid, c.relname AS relation_name, c.oid AS relation_oid, c.relkind::text AS relkind
              FROM akis_yayin_defteri.kurulum_kimligi k
              CROSS JOIN pg_catalog.pg_database d
              JOIN pg_catalog.pg_namespace n ON n.nspname = ?
              JOIN pg_catalog.pg_class c ON c.relnamespace = n.oid AND c.relname = ? AND c.relkind IN ('r', 'p')
             WHERE k.bilesen_kodu = 'AKIS_LEDGER' AND d.datname = current_database()
            """;

    private final PostgresTargetIdentityV1 canonicalizer;

    JdbcPostgresTargetIdentityReader() {
        this(new PostgresTargetIdentityV1());
    }

    JdbcPostgresTargetIdentityReader(PostgresTargetIdentityV1 canonicalizer) {
        this.canonicalizer = Objects.requireNonNull(canonicalizer, "Canonicalizer is required.");
    }

    @Override
    public CanonicalTargetIdentity read(Connection connection, String owner, String objectType, String objectName) {
        Objects.requireNonNull(connection, "PostgreSQL connection is required.");
        String schema = canonicalizer.requireIdentifier(owner, "Schema");
        String table = canonicalizer.requireIdentifier(objectName, "Object name");
        try (PreparedStatement statement = connection.prepareStatement(IDENTITY_SQL)) {
            statement.setString(1, schema);
            statement.setString(2, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new OracleTargetIdentityException("PostgreSQL target table or ledger installation was not found.");
                }
                VerifiedRelation relation = new VerifiedRelation(
                        resultSet.getString("installation_uuid"), resultSet.getString("database_name"), resultSet.getLong("database_oid"),
                        resultSet.getString("schema_name"), resultSet.getLong("schema_oid"), resultSet.getString("relation_name"),
                        resultSet.getLong("relation_oid"), resultSet.getString("relkind"));
                if (resultSet.next()) throw new OracleTargetIdentityException("PostgreSQL target identity query was ambiguous.");
                return canonicalizer.canonicalize(relation, objectType);
            }
        }
        catch (SQLException exception) {
            throw new OracleTargetIdentityException("PostgreSQL target identity could not be verified.", exception);
        }
    }
}
