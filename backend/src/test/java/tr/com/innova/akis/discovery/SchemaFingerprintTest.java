package tr.com.innova.akis.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.discovery.SchemaSnapshotModels.ColumnInput;
import tr.com.innova.akis.discovery.SchemaSnapshotModels.ConstraintInput;

class SchemaFingerprintTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SchemaFingerprint fingerprint = new SchemaFingerprint(objectMapper);

    @Test
    void fingerprintIsIndependentOfJsonPropertyAndSchemaItemOrder() throws Exception {
        ColumnInput first = column("ID", 1, "NUMBER");
        ColumnInput second = column("NAME", 2, "VARCHAR2");
        ConstraintInput primaryKey = constraint("PK_TEST", "PK", List.of("ID"), "{\"b\":2,\"a\":1}");
        ConstraintInput unique = constraint("UK_TEST", "UK", List.of("NAME"), "{\"x\":true}");

        String left = fingerprint.calculate(
                "19c", 1, objectMapper.readTree("{\"z\":2,\"a\":1}"),
                List.of(first, second), List.of(primaryKey, unique));
        String right = fingerprint.calculate(
                "19c", 1, objectMapper.readTree("{\"a\":1,\"z\":2}"),
                List.of(second, first), List.of(unique, primaryKey));

        assertEquals(left, right);
        assertEquals(64, left.length());
    }

    @Test
    void fingerprintChangesWhenSchemaChanges() throws Exception {
        String before = fingerprint.calculate(
                "19c", 1, objectMapper.createObjectNode(),
                List.of(column("ID", 1, "NUMBER")), List.of());
        String after = fingerprint.calculate(
                "19c", 1, objectMapper.createObjectNode(),
                List.of(column("ID", 1, "VARCHAR2")), List.of());

        assertNotEquals(before, after);
    }

    private ColumnInput column(String reference, int ordinal, String producerType) {
        return new ColumnInput(
                reference, producerType, "STRING", ordinal, null, null, null, null,
                false, null, reference);
    }

    private ConstraintInput constraint(
            String reference, String type, List<String> columns, String details) throws Exception {
        return new ConstraintInput(
                reference, type, true, 1, objectMapper.readTree(details), reference, columns);
    }
}
