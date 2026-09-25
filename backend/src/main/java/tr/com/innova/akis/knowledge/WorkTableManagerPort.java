package tr.com.innova.akis.knowledge;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/**
 * Technology port for the C$ work table of a staged mapping: create it on the work-owner (DDL) connection, hand the target
 * user read access, re-verify identity before every step and drop it once consumed. Implementations are Oracle today
 * ({@link OracleWorkTableManager}) and PostgreSQL next; the KM runtime never sees vendor DDL or locking.
 */
public interface WorkTableManagerPort {
    /** A work column as the staging technology will declare it; {@code ddlType} is validated by the adapter that creates the table. */
    record Column(String name, String ddlType) {
        public Column { StagedMappingDefinition.identifier(name); }
        /** Kept for the Oracle call sites; the same declared type under its technology-neutral name. */
        public String oracleType() { return ddlType; }
    }
    /** The created (or adopted) work table with the identity the adapter will re-verify: database, catalog object id and structure hash. */
    record Created(UUID uuid, String databaseIdentity, JdbcStagingTransfer.Table table, long objectId, String structureHash) { }

    default void dropIfExists(Connection control, String targetDatabaseIdentity, JdbcStagingTransfer.Table table, int timeout) {
        throw new UnsupportedOperationException("Restart cleanup is not supported by this work-table manager.");
    }
    Created create(Connection control, WorkObjectStore.Owner owner, String targetDatabaseIdentity, JdbcStagingTransfer.Table table,
            List<Column> columns, int timeout, Runnable checkpoint, WorkObjectStore.WorkArea workArea);
    void grantRead(Connection control, Created created, String targetUser, int timeout, Runnable checkpoint);
    void verify(Connection control, Created created, int timeout) throws SQLException;
    void cleanup(Connection control, WorkObjectStore.Owner owner, UUID object, int timeout);
    void cleanupReviewed(Connection control, UUID projectUuid, UUID runUuid, UUID object, int timeout);
}
