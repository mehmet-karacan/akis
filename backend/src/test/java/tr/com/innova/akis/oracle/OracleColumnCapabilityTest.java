package tr.com.innova.akis.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class OracleColumnCapabilityTest {

    @Test
    void classifiesTransferTypesObservedInTheOdiRepositoryInventory() {
        assertClassification("NUMBER", 19, 0, "INTEGER", "TRANSFER_SUPPORTED");
        assertClassification("NUMBER", 20, 0, "DECIMAL", "TRANSFER_SUPPORTED");
        assertClassification("NUMBER", null, null, "DECIMAL", "TRANSFER_SUPPORTED");
        assertClassification("VARCHAR2", null, null, "STRING", "TRANSFER_SUPPORTED");
        assertClassification("CLOB", null, null, "STRING", "TRANSFER_SUPPORTED");
        assertClassification("NCLOB", null, null, "STRING", "TRANSFER_SUPPORTED");
        assertClassification("NUMBER", 18, -2, "DECIMAL", "CATALOG_ONLY");
        assertClassification("NUMBER", null, 0, "DECIMAL", "CATALOG_ONLY");
    }

    @Test
    void keepsObservedLegacyAndLargeValueTypesVisibleWithoutPromisingTransfer() {
        assertClassification("DATE", null, null, "TIMESTAMP", "CATALOG_ONLY");
        assertClassification("LONG RAW", null, null, "BINARY", "CATALOG_ONLY");
    }

    @Test
    void distinguishesSupportedTimestampFromTimezoneAndUnknownTypes() {
        assertClassification("TIMESTAMP(6)", null, null,
                "TIMESTAMP", "TRANSFER_SUPPORTED");
        assertClassification("TIMESTAMP(9)", null, 9,
                "TIMESTAMP", "CATALOG_ONLY");
        assertClassification("TIMESTAMP(6) WITH TIME ZONE", null, null,
                "OFFSET_TIMESTAMP", "CATALOG_ONLY");
        assertClassification("XMLTYPE", null, null, "UNKNOWN", "UNSUPPORTED");
        assertClassification(null, null, null, "UNKNOWN", "UNSUPPORTED");
    }

    private void assertClassification(
            String producerType,
            Integer precision,
            Integer scale,
            String canonicalType,
            String executionCapability) {
        var result = OracleColumnCapability.classify(producerType, precision, scale);

        assertEquals(canonicalType, result.canonicalType());
        assertEquals(executionCapability, result.executionCapability());
    }
}
