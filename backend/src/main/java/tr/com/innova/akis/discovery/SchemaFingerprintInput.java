package tr.com.innova.akis.discovery;

import java.util.List;

import tools.jackson.databind.JsonNode;

/**
 * Public, immutable input of the schema fingerprint contract. Runtime
 * preflight uses the same contract as snapshot creation instead of maintaining
 * a second canonicalization algorithm.
 */
public record SchemaFingerprintInput(
        String engineVersion,
        int propertyVersion,
        JsonNode properties,
        List<Column> columns,
        List<Constraint> constraints) {

    public SchemaFingerprintInput {
        properties = properties.deepCopy();
        columns = List.copyOf(columns);
        constraints = List.copyOf(constraints);
    }

    @Override
    public JsonNode properties() {
        return properties.deepCopy();
    }

    public record Column(
            String reference,
            String producerType,
            String canonicalType,
            int ordinal,
            Integer precision,
            Integer scale,
            Long length,
            Integer timePrecision,
            boolean nullable,
            String defaultExpression,
            String name) {
    }

    public record Constraint(
            String externalReference,
            String type,
            boolean enabled,
            int detailVersion,
            JsonNode details,
            String name,
            List<String> columnReferences) {

        public Constraint {
            details = details.deepCopy();
            columnReferences = List.copyOf(columnReferences);
        }

        @Override
        public JsonNode details() {
            return details.deepCopy();
        }
    }
}
