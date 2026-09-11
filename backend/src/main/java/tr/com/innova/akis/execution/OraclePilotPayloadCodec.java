package tr.com.innova.akis.execution;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/** Canonical immutable cell codec and deterministic bounded batch framing. */
final class OraclePilotPayloadCodec {

    static final int MAXIMUM_CELL_BYTES = 1_048_576;
    static final int MAXIMUM_BATCH_BYTES = 16_777_216;

    private static final String DOMAIN = "AKIS_ORACLE_PILOT_BATCH";
    private static final int VERSION = 1;

    List<OraclePilotColumn> columns(
            ResultSetMetaData metadata, List<String> sourceColumns) throws SQLException {
        if (metadata.getColumnCount() != sourceColumns.size()) {
            throw unsupportedType();
        }
        java.util.ArrayList<OraclePilotColumn> columns =
                new java.util.ArrayList<>(sourceColumns.size());
        for (int index = 1; index <= sourceColumns.size(); index++) {
            columns.add(new OraclePilotColumn(
                    sourceColumns.get(index - 1), type(metadata, index)));
        }
        return List.copyOf(columns);
    }

    OraclePilotCell read(ResultSet resultSet, int index, OraclePilotColumnType type)
            throws SQLException {
        return switch (type) {
            case NUMBER -> number(resultSet.getBigDecimal(index));
            case VARCHAR2 -> text(resultSet.getString(index));
            case TIMESTAMP -> timestamp(resultSet.getTimestamp(index));
        };
    }

    OraclePilotBatch batch(
            String runtimePlanHash,
            List<OraclePilotColumn> columns,
            List<List<OraclePilotCell>> rows) {
        EncodedPayload encoded = encode(runtimePlanHash, columns, rows);
        return new OraclePilotBatch(
                runtimePlanHash, columns, rows, encoded.hash(), encoded.byteCount());
    }

    PayloadBudget budget(
            String runtimePlanHash, List<OraclePilotColumn> columns) {
        if (runtimePlanHash == null || columns == null) {
            throw invalidBatch();
        }
        long bytes = framedSize(DOMAIN) + Integer.BYTES
                + framedSize(runtimePlanHash) + Integer.BYTES;
        for (OraclePilotColumn column : columns) {
            if (column == null || column.sourceColumn() == null || column.type() == null) {
                throw invalidBatch();
            }
            bytes += framedSize(column.sourceColumn()) + framedSize(column.type().name());
        }
        bytes += Integer.BYTES;
        return new PayloadBudget(columns, bytes);
    }

    void verify(OraclePilotBatch batch) {
        EncodedPayload encoded = encode(
                batch.runtimePlanHash(), batch.columns(), batch.rows());
        if (batch.byteCount() != encoded.byteCount()
                || !constantTimeEquals(batch.payloadHash(), encoded.hash())) {
            throw invalidBatch();
        }
    }

    private OraclePilotColumnType type(ResultSetMetaData metadata, int index)
            throws SQLException {
        int jdbcType = metadata.getColumnType(index);
        String typeName = metadata.getColumnTypeName(index);
        String canonicalTypeName = typeName == null
                ? "" : typeName.toUpperCase(Locale.ROOT);
        if ((jdbcType == Types.NUMERIC || jdbcType == Types.DECIMAL)
                && canonicalTypeName.equals("NUMBER")) {
            return OraclePilotColumnType.NUMBER;
        }
        if (jdbcType == Types.VARCHAR && canonicalTypeName.equals("VARCHAR2")) {
            return OraclePilotColumnType.VARCHAR2;
        }
        if (jdbcType == Types.TIMESTAMP
                && canonicalTypeName.startsWith("TIMESTAMP")
                && !canonicalTypeName.contains("TIME ZONE")
                && metadata.getScale(index) >= 0
                && metadata.getScale(index) <= 6) {
            return OraclePilotColumnType.TIMESTAMP;
        }
        throw unsupportedType();
    }

    private OraclePilotCell number(BigDecimal value) {
        if (value == null) {
            return new OraclePilotCell(OraclePilotColumnType.NUMBER, null);
        }
        BigDecimal normalized = value.signum() == 0
                ? BigDecimal.ZERO : value.stripTrailingZeros();
        return checked(new OraclePilotCell(
                OraclePilotColumnType.NUMBER, normalized.toPlainString()));
    }

    private OraclePilotCell text(String value) {
        return checked(new OraclePilotCell(OraclePilotColumnType.VARCHAR2, value));
    }

    private OraclePilotCell timestamp(Timestamp value) {
        if (value == null) {
            return new OraclePilotCell(OraclePilotColumnType.TIMESTAMP, null);
        }
        LocalDateTime local = value.toLocalDateTime();
        if (local.getNano() % 1_000 != 0) {
            throw unsupportedType();
        }
        return checked(new OraclePilotCell(
                OraclePilotColumnType.TIMESTAMP,
                DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(local)));
    }

    private OraclePilotCell checked(OraclePilotCell cell) {
        if (cell.canonicalValue() != null
                && utf8(cell.canonicalValue()).length > MAXIMUM_CELL_BYTES) {
            throw new OraclePilotDataException(
                    OraclePilotDataException.Failure.SOURCE_CELL_LIMIT_EXCEEDED,
                    "An Oracle pilot source cell exceeds the byte limit.");
        }
        return cell;
    }

    private EncodedPayload encode(
            String runtimePlanHash,
            List<OraclePilotColumn> columns,
            List<List<OraclePilotCell>> rows) {
        if (runtimePlanHash == null || columns == null || rows == null) {
            throw invalidBatch();
        }
        CanonicalSink sink = new CanonicalSink();
        sink.field(DOMAIN);
        sink.integer(VERSION);
        sink.field(runtimePlanHash);
        sink.integer(columns.size());
        for (OraclePilotColumn column : columns) {
            if (column == null || column.sourceColumn() == null || column.type() == null) {
                throw invalidBatch();
            }
            sink.field(column.sourceColumn());
            sink.field(column.type().name());
        }
        sink.integer(rows.size());
        List<List<OraclePilotCell>> canonicalRows = new ArrayList<>(rows);
        for (List<OraclePilotCell> row : canonicalRows) {
            if (row == null || row.size() != columns.size()) {
                throw invalidBatch();
            }
            for (int index = 0; index < row.size(); index++) {
                OraclePilotCell cell = row.get(index);
                if (cell == null || cell.type() != columns.get(index).type()) {
                    throw invalidBatch();
                }
                validateCanonical(cell);
            }
        }
        canonicalRows.sort(rowComparator());
        for (List<OraclePilotCell> row : canonicalRows) {
            sink.integer(row.size());
            for (int index = 0; index < row.size(); index++) {
                OraclePilotCell cell = row.get(index);
                sink.field(cell.type().name());
                sink.singleByte(cell.isNull() ? 0 : 1);
                if (!cell.isNull()) {
                    byte[] bytes = utf8(cell.canonicalValue());
                    if (bytes.length > MAXIMUM_CELL_BYTES) {
                        throw new OraclePilotDataException(
                                OraclePilotDataException.Failure.SOURCE_CELL_LIMIT_EXCEEDED,
                                "An Oracle pilot source cell exceeds the byte limit.");
                    }
                    sink.field(bytes);
                }
            }
        }
        return sink.finish();
    }

    private Comparator<List<OraclePilotCell>> rowComparator() {
        return (left, right) -> {
            for (int index = 0; index < left.size(); index++) {
                OraclePilotCell leftCell = left.get(index);
                OraclePilotCell rightCell = right.get(index);
                int compared = Boolean.compare(leftCell.isNull(), rightCell.isNull());
                if (compared != 0) {
                    return compared;
                }
                if (!leftCell.isNull()) {
                    compared = java.util.Arrays.compareUnsigned(
                            utf8(leftCell.canonicalValue()),
                            utf8(rightCell.canonicalValue()));
                    if (compared != 0) {
                        return compared;
                    }
                }
            }
            return 0;
        };
    }

    private void validateCanonical(OraclePilotCell cell) {
        if (cell.isNull()) {
            return;
        }
        String value = cell.canonicalValue();
        try {
            switch (cell.type()) {
                case NUMBER -> {
                    BigDecimal decimal = new BigDecimal(value);
                    String normalized = decimal.signum() == 0
                            ? "0" : decimal.stripTrailingZeros().toPlainString();
                    if (!normalized.equals(value)) {
                        throw invalidBatch();
                    }
                }
                case VARCHAR2 -> utf8(value);
                case TIMESTAMP -> {
                    LocalDateTime parsed = LocalDateTime.parse(
                            value, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                    if (parsed.getNano() % 1_000 != 0
                            || !DateTimeFormatter.ISO_LOCAL_DATE_TIME
                                    .format(parsed).equals(value)) {
                        throw invalidBatch();
                    }
                }
            }
        }
        catch (NumberFormatException | DateTimeParseException exception) {
            throw invalidBatch();
        }
    }

    static byte[] utf8(String value) {
        try {
            ByteBuffer bytes = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
            byte[] result = new byte[bytes.remaining()];
            bytes.get(result);
            return result;
        }
        catch (CharacterCodingException exception) {
            throw invalidBatch();
        }
    }

    private static long framedSize(String value) {
        return Integer.BYTES + utf8(value).length;
    }

    private static boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }

    private static OraclePilotDataException unsupportedType() {
        return new OraclePilotDataException(
                OraclePilotDataException.Failure.UNSUPPORTED_SOURCE_TYPE,
                "The Oracle pilot source contains an unsupported JDBC type.");
    }

    private static OraclePilotDataException invalidBatch() {
        return new OraclePilotDataException(
                OraclePilotDataException.Failure.INVALID_BATCH,
                "The Oracle pilot batch is not canonical.");
    }

    private static final class CanonicalSink {
        private final MessageDigest digest;
        private long count;

        private CanonicalSink() {
            try {
                digest = MessageDigest.getInstance("SHA-256");
            }
            catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("SHA-256 is unavailable.", exception);
            }
        }

        private void field(String value) {
            field(utf8(value));
        }

        private void field(byte[] value) {
            integer(value.length);
            add(value);
        }

        private void integer(int value) {
            add(ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
        }

        private void singleByte(int value) {
            add(new byte[] {(byte) value});
        }

        private void add(byte[] bytes) {
            if (count + bytes.length > MAXIMUM_BATCH_BYTES) {
                throw new OraclePilotDataException(
                        OraclePilotDataException.Failure.SOURCE_BATCH_LIMIT_EXCEEDED,
                        "The Oracle pilot source batch exceeds the byte limit.");
            }
            digest.update(bytes);
            count += bytes.length;
        }

        private EncodedPayload finish() {
            return new EncodedPayload(HexFormat.of().formatHex(digest.digest()), count);
        }
    }

    final class PayloadBudget {
        private final List<OraclePilotColumn> columns;
        private long byteCount;

        private PayloadBudget(List<OraclePilotColumn> columns, long byteCount) {
            this.columns = List.copyOf(columns);
            this.byteCount = byteCount;
            requireBatchLimit(byteCount);
        }

        void accept(List<OraclePilotCell> row) {
            if (row == null || row.size() != columns.size()) {
                throw invalidBatch();
            }
            long added = Integer.BYTES;
            for (int index = 0; index < row.size(); index++) {
                OraclePilotCell cell = row.get(index);
                if (cell == null || cell.type() != columns.get(index).type()) {
                    throw invalidBatch();
                }
                validateCanonical(cell);
                added += framedSize(cell.type().name()) + 1;
                if (!cell.isNull()) {
                    byte[] value = utf8(cell.canonicalValue());
                    if (value.length > MAXIMUM_CELL_BYTES) {
                        throw new OraclePilotDataException(
                                OraclePilotDataException.Failure.SOURCE_CELL_LIMIT_EXCEEDED,
                                "An Oracle pilot source cell exceeds the byte limit.");
                    }
                    added += Integer.BYTES + value.length;
                }
            }
            requireBatchLimit(byteCount + added);
            byteCount += added;
        }

        private void requireBatchLimit(long candidate) {
            if (candidate > MAXIMUM_BATCH_BYTES) {
                throw new OraclePilotDataException(
                        OraclePilotDataException.Failure.SOURCE_BATCH_LIMIT_EXCEEDED,
                        "The Oracle pilot source batch exceeds the byte limit.");
            }
        }
    }

    private record EncodedPayload(String hash, long byteCount) {
    }
}
