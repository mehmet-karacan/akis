package tr.com.innova.akis.postgres;

import java.util.Locale;
import tr.com.innova.akis.oracle.OracleColumnCapability.Classification;

/**
 * Classifies a browsed PostgreSQL column (JDBC TYPE_NAME) for the discovery screen. Faz A writes numeric, character,
 * timestamp and text targets; everything else is catalogued but not transferable yet.
 */
public final class PostgresColumnCapability {
    private PostgresColumnCapability() { }

    public static Classification classify(String producerType, Integer precision, Integer scale) {
        String type = producerType == null ? "" : producerType.strip().toLowerCase(Locale.ROOT);
        return switch (type) {
            case "numeric", "decimal" -> new Classification(
                    precision != null && scale != null && scale == 0 && precision <= 19 ? "INTEGER" : "DECIMAL", "TRANSFER_SUPPORTED");
            case "int2", "smallint", "int4", "integer", "serial", "int8", "bigint", "bigserial" -> new Classification("INTEGER", "TRANSFER_SUPPORTED");
            case "varchar", "character varying", "bpchar", "character", "char", "text" -> new Classification("STRING", "TRANSFER_SUPPORTED");
            case "timestamp", "timestamp without time zone" -> new Classification("TIMESTAMP", "TRANSFER_SUPPORTED");
            case "timestamptz", "timestamp with time zone" -> new Classification("OFFSET_TIMESTAMP", "CATALOG_ONLY");
            case "date" -> new Classification("DATE", "CATALOG_ONLY");
            case "bool", "boolean" -> new Classification("BOOLEAN", "CATALOG_ONLY");
            case "bytea" -> new Classification("BINARY", "CATALOG_ONLY");
            case "float4", "real" -> new Classification("FLOAT32", "CATALOG_ONLY");
            case "float8", "double precision" -> new Classification("FLOAT64", "CATALOG_ONLY");
            case "uuid" -> new Classification("STRING", "CATALOG_ONLY");
            default -> new Classification("UNKNOWN", "UNSUPPORTED");
        };
    }
}
