package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import tr.com.innova.akis.execution.PostgresTargetIdentityV1.VerifiedRelation;

class PostgresTargetIdentityV1Test {
    private static final String INSTALLATION = "3f1a0c6e-9b2d-4f0e-8a7b-1c2d3e4f5a6b";
    private final PostgresTargetIdentityV1 canonicalizer = new PostgresTargetIdentityV1();

    @Test
    void hashCoversInstallationDatabaseNamespaceAndRelationOids() {
        var base = canonicalizer.canonicalize(relation(INSTALLATION, 16384L, 2200L, 90001L, "r"), "TABLE");
        assertEquals(1, base.targetIdentityVersion());
        assertEquals("akis_metadata", base.databaseUniqueName());
        assertEquals(INSTALLATION, base.containerName());
        assertEquals("akis_pg_target", base.owner());
        assertEquals("STG_HAKEDIS_TIPI", base.objectName());
        assertEquals(64, base.canonicalTargetHash().length());
        assertEquals(base.canonicalTargetHash(), canonicalizer.canonicalize(relation(INSTALLATION, 16384L, 2200L, 90001L, "r"), "TABLE").canonicalTargetHash());
        // Dropped and recreated table -> new relation oid -> different target.
        assertNotEquals(base.canonicalTargetHash(), canonicalizer.canonicalize(relation(INSTALLATION, 16384L, 2200L, 90002L, "r"), "TABLE").canonicalTargetHash());
        // Same catalog restored into another ledger installation -> different target.
        assertNotEquals(base.canonicalTargetHash(), canonicalizer.canonicalize(relation("00000000-0000-4000-8000-000000000001", 16384L, 2200L, 90001L, "r"), "TABLE").canonicalTargetHash());
    }

    @Test
    void rejectsViewsAndInvalidIdentifiers() {
        assertThrows(OracleTargetIdentityException.class, () -> canonicalizer.canonicalize(relation(INSTALLATION, 1L, 2L, 3L, "v"), "TABLE"));
        assertThrows(OracleTargetIdentityException.class, () -> canonicalizer.canonicalize(relation(INSTALLATION, 1L, 2L, 3L, "r"), "VIEW"));
        assertThrows(OracleTargetIdentityException.class, () -> canonicalizer.canonicalize(relation("not-a-uuid", 1L, 2L, 3L, "r"), "TABLE"));
        assertThrows(OracleTargetIdentityException.class, () -> canonicalizer.requireIdentifier("bad name;", "Schema"));
        assertEquals("MixedCase_1", canonicalizer.requireIdentifier("MixedCase_1", "Schema"));
    }

    private static VerifiedRelation relation(String installation, long databaseOid, long schemaOid, long relationOid, String relkind) {
        return new VerifiedRelation(installation, "akis_metadata", databaseOid, "akis_pg_target", schemaOid, "STG_HAKEDIS_TIPI", relationOid, relkind);
    }
}
