package tr.com.innova.akis.execution;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JdbcPostgresSchemaPreflightWriteModeTest {
    @Test void mergeAllowsTargetsReferencedByForeignKeys() {
        assertFalse(JdbcPostgresSchemaPreflight.blocksInboundForeignKeys("MERGE"));
        assertFalse(JdbcPostgresSchemaPreflight.blocksInboundForeignKeys(" merge "));
    }

    @Test void dependencyOrderedTruncateIsAllowedButAtomicDeleteAndUnknownRemainFailClosed() {
        assertFalse(JdbcPostgresSchemaPreflight.blocksInboundForeignKeys("TRUNCATE_LOAD"));
        assertTrue(JdbcPostgresSchemaPreflight.blocksInboundForeignKeys("ATOMIC_DELETE_INSERT"));
        assertTrue(JdbcPostgresSchemaPreflight.blocksInboundForeignKeys(null));
    }
}
