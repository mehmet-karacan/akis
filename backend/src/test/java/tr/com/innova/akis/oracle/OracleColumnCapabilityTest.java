package tr.com.innova.akis.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class OracleColumnCapabilityTest {

    @Test
    void classifiesTransferTypesObservedInSourceRepositoryInventory() {
        assertClassification("NUMBER", 19, 0, "INTEGER", "TRANSFER_SUPPORTED");
        assertClassification("NUMBER", 20, 0, "DECIMAL", "TRANSFER_SUPPORTED");
        assertClassification("NUMBER", null, null, "DECIMAL", "TRANSFER_SUPPORTED");
        assertClassification("VARCHAR2", null, null, "STRING", "TRANSFER_SUPPORTED");
        assertClassification("CLOB", null, null, "STRING", "TRANSFER_SUPPORTED");
        assertClassification("NCLOB", null, null, "STRING", "TRANSFER_SUPPORTED");
        assertClassification("NUMBER", 18, -2, "DECIMAL", "TRANSFER_SUPPORTED");
        assertClassification("NUMBER", 12, -84, "DECIMAL", "TRANSFER_SUPPORTED");
        assertClassification("NUMBER", 12, 127, "DECIMAL", "TRANSFER_SUPPORTED");
        assertClassification("NUMBER", null, 0, "DECIMAL", "CATALOG_ONLY");
        assertClassification("NUMBER", 0, 0, "DECIMAL", "CATALOG_ONLY");
        assertClassification("NUMBER", 39, 0, "DECIMAL", "CATALOG_ONLY");
        assertClassification("NUMBER", 12, -85, "DECIMAL", "CATALOG_ONLY");
        assertClassification("NUMBER", 12, 128, "DECIMAL", "CATALOG_ONLY");
    }

    @Test
    void supportsTypedBenchmarkValuesWhileKeepingLegacyTypesCatalogOnly() {
        assertClassification("DATE", null, null, "TIMESTAMP", "TRANSFER_SUPPORTED");
        assertClassification("LONG RAW", null, null, "BINARY", "CATALOG_ONLY");
        assertClassification("BINARY_FLOAT", null, null, "FLOAT32", "TRANSFER_SUPPORTED");
        assertClassification("BINARY_DOUBLE", null, null, "FLOAT64", "TRANSFER_SUPPORTED");
        assertClassification("FLOAT(126)", 126, null, "FLOAT64", "CATALOG_ONLY");
        assertClassification("RAW(32)", null, null, "BINARY", "TRANSFER_SUPPORTED");
        assertClassification("BLOB", null, null, "BINARY", "TRANSFER_SUPPORTED");
        assertClassification("CHAR(8)", null, null, "STRING", "TRANSFER_SUPPORTED");
        assertClassification("NCHAR(8)", null, null, "STRING", "TRANSFER_SUPPORTED");
        assertClassification("NVARCHAR2(120)", null, null, "STRING", "TRANSFER_SUPPORTED");
    }

    @Test
    void distinguishesSupportedTimestampFromTimezoneAndUnknownTypes() {
        assertClassification("TIMESTAMP(6)", null, null,
                "TIMESTAMP", "TRANSFER_SUPPORTED");
        assertClassification("TIMESTAMP(9)", null, 9,
                "TIMESTAMP", "CATALOG_ONLY");
        assertClassification("TIMESTAMP(6) WITH TIME ZONE", null, null,
                "OFFSET_TIMESTAMP", "TRANSFER_SUPPORTED");
        assertClassification("TIMESTAMP(9)", null, null, "TIMESTAMP", "CATALOG_ONLY");
        assertClassification("TIMESTAMP(9) WITH TIME ZONE", null, null, "OFFSET_TIMESTAMP", "CATALOG_ONLY");
        assertClassification("TIMESTAMP WITH TIME ZONE", null, -1, "OFFSET_TIMESTAMP", "CATALOG_ONLY");
        assertClassification("TIMESTAMP WITH LOCAL TIME ZONE", null, 6, "OFFSET_TIMESTAMP", "CATALOG_ONLY");
        assertClassification("CHAR(2001)", null, null, "STRING", "CATALOG_ONLY");
        assertClassification("RAW(32768)", null, null, "BINARY", "CATALOG_ONLY");
        assertClassification("XMLTYPE", null, null, "UNKNOWN", "UNSUPPORTED");
        assertClassification(null, null, null, "UNKNOWN", "UNSUPPORTED");
    }

    @Test
    void allFiftyBenchmarkTypesHaveTheCoordinatedRuntimeCapability() {
        for (var raw : OracleSchemaSnapshotCodecV1Test.skyDitColumns()) {
            var result = OracleColumnCapability.classify(raw.dataType(),
                    raw.precision() != null ? raw.precision()
                            : raw.charLength() == null ? null : Math.toIntExact(raw.charLength()),
                    raw.timePrecision() != null ? raw.timePrecision() : raw.scale());
            assertEquals("TRANSFER_SUPPORTED", result.executionCapability(), raw.name());
        }
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
