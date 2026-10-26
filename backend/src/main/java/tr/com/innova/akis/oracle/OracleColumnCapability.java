package tr.com.innova.akis.oracle;

import java.util.Locale;

/** Classifies metadata against the supported typed transfer contract. */
public final class OracleColumnCapability {

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
                    && scale >= -84 && scale <= 127;
            String canonical = precision != null && scale != null
                    && scale == 0 && precision >= 1 && precision <= 19
                    ? "INTEGER"
                    : "DECIMAL";
            return new Classification(
                    canonical, supportedNumber ? "TRANSFER_SUPPORTED" : "CATALOG_ONLY");
        }
        if ("VARCHAR2".equals(type) || "NVARCHAR2".equals(type)
                || "CHAR".equals(type) || "NCHAR".equals(type)) {
            int maximumLength = "CHAR".equals(type) || "NCHAR".equals(type) ? 2_000 : 32_767;
            return new Classification("STRING", boundedLength(producerType, precision, maximumLength)
                    ? "TRANSFER_SUPPORTED" : "CATALOG_ONLY");
        }
        if ("TIMESTAMP".equals(type) || "TIMESTAMP WITH TIME ZONE".equals(type)) {
            Integer declared = declaredSize(producerType);
            boolean supportedTimestamp = (scale == null || scale >= 0 && scale <= 6)
                    && (declared == null || declared >= 0 && declared <= 6);
            return new Classification(
                    "TIMESTAMP".equals(type) ? "TIMESTAMP" : "OFFSET_TIMESTAMP",
                    supportedTimestamp ? "TRANSFER_SUPPORTED" : "CATALOG_ONLY");
        }
        return switch (type) {
            case "BINARY_FLOAT" -> new Classification("FLOAT32", "TRANSFER_SUPPORTED");
            case "BINARY_DOUBLE" -> new Classification("FLOAT64", "TRANSFER_SUPPORTED");
            case "FLOAT" -> new Classification("FLOAT64", "CATALOG_ONLY");
            case "DATE" -> new Classification("TIMESTAMP", "TRANSFER_SUPPORTED");
            case "CLOB", "NCLOB" -> new Classification("STRING", "TRANSFER_SUPPORTED");
            case "RAW" -> new Classification("BINARY", boundedLength(producerType, precision, 32_767)
                    ? "TRANSFER_SUPPORTED" : "CATALOG_ONLY");
            case "BLOB" -> new Classification("BINARY", "TRANSFER_SUPPORTED");
            case "LONG RAW" ->
                    new Classification("BINARY", "CATALOG_ONLY");
            case "LONG" ->
                    new Classification("STRING", "CATALOG_ONLY");
            case "TIMESTAMP WITH LOCAL TIME ZONE" ->
                    new Classification("OFFSET_TIMESTAMP", "CATALOG_ONLY");
            default -> new Classification("UNKNOWN", "UNSUPPORTED");
        };
    }

    private static boolean boundedLength(String producerType, Integer jdbcSize, int maximum) {
        Integer declared = declaredSize(producerType);
        return (declared == null || declared >= 1 && declared <= maximum)
                && (jdbcSize == null || jdbcSize >= 1 && jdbcSize <= maximum);
    }

    private static Integer declaredSize(String producerType) {
        int opening = producerType.indexOf('(');
        if (opening < 0) return null;
        int closing = producerType.indexOf(')', opening);
        if (closing < 0) return -1;
        try {
            return Integer.valueOf(producerType.substring(opening + 1, closing).strip()
                    .replaceFirst("(?i)\\s+(CHAR|BYTE)$", ""));
        }
        catch (NumberFormatException exception) {
            return -1;
        }
    }

    private static String normalized(String producerType) {
        if (producerType == null || producerType.isBlank()) {
            return "";
        }
        return producerType.strip().toUpperCase(Locale.ROOT)
                .replaceAll("\\s*\\([^)]*\\)", "")
                .replaceAll("\\s+", " ");
    }

    public record Classification(String canonicalType, String executionCapability) {
    }
}
