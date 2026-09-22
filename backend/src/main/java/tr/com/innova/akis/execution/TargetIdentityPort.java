package tr.com.innova.akis.execution;

import java.sql.Connection;

/**
 * Technology port for reading the canonical identity of a target object (database/container, owner, object) on an open
 * session. The Oracle adapter is {@link JdbcOracleTargetIdentityReader} (SYS_CONTEXT + ALL_OBJECTS); a PostgreSQL adapter
 * reads the installation id, database, namespace and relation oid. Every fence, publish and reconciliation compares the
 * identity read here with the one pinned at publication time. The identity record is still the Oracle V1 shape
 * (database unique name, container); the versioned generic model arrives with the PostgreSQL identity migration.
 */
interface TargetIdentityPort {
    OracleTargetIdentityV1.CanonicalTargetIdentity read(Connection connection, String owner, String objectType, String objectName);
}
