package tr.com.innova.akis.execution;

import java.sql.Connection;

/**
 * Technology port for reading the canonical identity of a target object (owner, object) on an open session. The Oracle
 * adapter is {@link JdbcOracleTargetIdentityReader} (SYS_CONTEXT + ALL_OBJECTS); the PostgreSQL adapter is
 * {@link JdbcPostgresTargetIdentityReader} (installation uuid, database, namespace and relation oid). Every fence,
 * publish and reconciliation compares the identity read here with the one pinned at publication time; only
 * {@code targetIdentityVersion} + {@code canonicalTargetHash} are ever compared, so {@code site}/{@code container} exist
 * for diagnostics only.
 */
interface TargetIdentityPort {

    CanonicalTargetIdentity read(Connection connection, String owner, String objectType, String objectName);

    /**
     * Technology-neutral canonical target identity. {@code owner}/{@code objectType}/{@code objectName} name the target
     * object and are meaningful for every technology; {@code site}/{@code container} are free-form technology-specific
     * diagnostic labels (Oracle: database unique name / container (PDB) name; PostgreSQL: database name / ledger
     * installation uuid) never compared across technologies. What actually makes one target the same target across a
     * drop-and-recreate is folded into {@code canonicalPayload}/{@code canonicalTargetHash} by each technology's own
     * canonicalizer.
     */
    record CanonicalTargetIdentity(
            String technologyCode,
            int targetIdentityVersion,
            String site,
            String container,
            String owner,
            String objectType,
            String objectName,
            byte[] canonicalPayload,
            String canonicalTargetHash) {

        public CanonicalTargetIdentity {
            canonicalPayload = canonicalPayload.clone();
        }

        @Override
        public byte[] canonicalPayload() {
            return canonicalPayload.clone();
        }
    }
}
