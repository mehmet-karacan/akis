package tr.com.innova.akis.oracle;

import java.util.Locale;

/** Classifies discovered Oracle columns without widening the execution runtime. */
final class OracleColumnCapability {

    private OracleColumnCapability() {
    }

    static Classification classify(
            String producerType,
            Integer precision,
            Integer scale) {
        String type = normalized(producerType);
        if ("NUMBER".equals(type)) {
            boolean bareNumber = precision == null && scale == null;
            boolean supportedNumber = bareNumber
                    || precision != null && scale != null
                    && precision >= 1 && precision <= 38
                    && scale >= 0 && scale <= 127;
            String canonical = precision != null && scale != null
                    && scale == 0 && precision <= 19
                    ? "INTEGER"
                    : "DECIMAL";
            return new Classification(
                    canonical, supportedNumber ? "TRANSFER_SUPPORTED" : "CATALOG_ONLY");
        }
        if ("VARCHAR2".equals(type)) {
            return new Classification("STRING", "TRANSFER_SUPPORTED");
        }
        if ("TIMESTAMP".equals(type)) {
            boolean supportedTimestamp = scale == null || scale >= 0 && scale <= 6;
            return new Classification(
                    "TIMESTAMP",
                    supportedTimestamp ? "TRANSFER_SUPPORTED" : "CATALOG_ONLY");
        }
        return switch (type) {
            case "DATE" -> new Classification("TIMESTAMP", "CATALOG_ONLY");
            case "CLOB", "NCLOB" -> new Classification("TEXT", "CATALOG_ONLY");
            case "RAW", "LONG RAW", "BLOB" ->
                    new Classification("BINARY", "CATALOG_ONLY");
            case "CHAR", "NCHAR", "NVARCHAR2", "LONG" ->
                    new Classification("STRING", "CATALOG_ONLY");
            case "TIMESTAMP WITH TIME ZONE", "TIMESTAMP WITH LOCAL TIME ZONE" ->
                    new Classification("OFFSET_TIMESTAMP", "CATALOG_ONLY");
            default -> new Classification("UNKNOWN", "UNSUPPORTED");
        };
    }

    private static String normalized(String producerType) {
        if (producerType == null || producerType.isBlank()) {
            return "";
        }
        return producerType.strip().toUpperCase(Locale.ROOT)
                .replaceAll("\\s*\\([^)]*\\)", "")
                .replaceAll("\\s+", " ");
    }

    record Classification(String canonicalType, String executionCapability) {
    }
}
