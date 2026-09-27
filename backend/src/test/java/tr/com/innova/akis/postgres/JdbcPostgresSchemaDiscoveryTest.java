package tr.com.innova.akis.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import tr.com.innova.akis.metadata.ApiException;
import tr.com.innova.akis.oracle.OracleDiscoveryModels;
import tr.com.innova.akis.oracle.OracleDiscoveryService;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.ConnectionProfile;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.Credentials;

import tools.jackson.databind.ObjectMapper;

class JdbcPostgresSchemaDiscoveryTest {

    @Test
    void mapsJdbcTableTypesToProviderNeutralTypes() {
        assertEquals(OracleDiscoveryModels.TABLE, JdbcPostgresSchemaDiscovery.objectTypeOf("TABLE"));
        assertEquals(OracleDiscoveryModels.PARTITIONED_TABLE, JdbcPostgresSchemaDiscovery.objectTypeOf("PARTITIONED TABLE"));
        assertEquals(OracleDiscoveryModels.VIEW, JdbcPostgresSchemaDiscovery.objectTypeOf("VIEW"));
        assertEquals(OracleDiscoveryModels.MATERIALIZED_VIEW, JdbcPostgresSchemaDiscovery.objectTypeOf("MATERIALIZED VIEW"));
        assertEquals("FOREIGN TABLE", JdbcPostgresSchemaDiscovery.objectTypeOf("FOREIGN TABLE"));
    }
}
