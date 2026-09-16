package tr.com.innova.akis.execution;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** SCN-pinned, ordered Oracle reader that never buffers more than one commit batch. */
final class OracleRangeTransferReader implements StreamingRowReader {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Z][A-Z0-9_$#]{0,127}");
    private final Connection connection;
    private final TransferExecutionPlan plan;

    OracleRangeTransferReader(Connection connection, TransferExecutionPlan plan) {
        this.connection = Objects.requireNonNull(connection);
        this.plan = Objects.requireNonNull(plan);
    }

    @Override
    public ReadResult read(Range range, BatchConsumer consumer) {
        Objects.requireNonNull(range);
        Objects.requireNonNull(consumer);
        String sql = sql(range.lowerExclusive() != null);
        TransferBufferBudget budget = new TransferBufferBudget(
                plan.maximumRowsPerBatch(), plan.maximumBytesPerBatch());
        List<Row> rows = new ArrayList<>();
        long totalRows = 0;
        long totalBytes = 0;
        BigDecimal lastCommitted = range.lowerExclusive();
        BigDecimal previousKey = range.lowerExclusive();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int parameter = 1;
            if (range.lowerExclusive() != null) statement.setBigDecimal(parameter++, range.lowerExclusive());
            statement.setBigDecimal(parameter, range.upperInclusive());
            statement.setFetchSize((int) Math.min(plan.maximumRowsPerBatch(), 10_000L));
            try (ResultSet resultSet = statement.executeQuery()) {
                ResultSetMetaData metadata = resultSet.getMetaData();
                while (resultSet.next()) {
                    Row row = canonicalRow(resultSet, metadata);
                    if (previousKey != null && row.key().compareTo(previousKey) <= 0) {
                        throw new SQLException("Range key is not strictly increasing.");
                    }
                    if (row.key().compareTo(range.upperInclusive()) > 0) {
                        throw new SQLException("Range key exceeds the pinned upper boundary.");
                    }
                    previousKey = row.key();
                    if (!budget.canAccept(row.canonicalBytes()) && !rows.isEmpty()) {
                        Batch batch = batch(rows, budget.bytes());
                        if (!consumer.commit(batch)) return new ReadResult(totalRows, totalBytes, lastCommitted);
                        totalRows = Math.addExact(totalRows, budget.rows());
                        totalBytes = Math.addExact(totalBytes, budget.bytes());
                        lastCommitted = batch.lastKey();
                        rows.clear();
                        budget.reset();
                    }
                    budget.accept(row.canonicalBytes());
                    rows.add(row);
                }
                if (!rows.isEmpty()) {
                    Batch batch = batch(rows, budget.bytes());
                    if (consumer.commit(batch)) {
                        totalRows = Math.addExact(totalRows, budget.rows());
                        totalBytes = Math.addExact(totalBytes, budget.bytes());
                        lastCommitted = batch.lastKey();
                    }
                }
                else if (totalRows == 0) {
                    Batch empty = emptyBatch(range.upperInclusive());
                    if (consumer.commit(empty)) lastCommitted = range.upperInclusive();
                }
                return new ReadResult(totalRows, totalBytes, lastCommitted);
            }
        }
        catch (SQLException exception) {
            throw new OracleRangeReadException("ORACLE_RANGE_READ_FAILED", exception);
        }
    }

    private String sql(boolean hasLowerBoundary) {
        String owner = identifier(plan.sourceOwner());
        String table = identifier(plan.sourceTable());
        String key = identifier(plan.numericKeyColumn());
        String columns = plan.columns().stream().map(mapping -> identifier(mapping.source()))
                .reduce((left, right) -> left + "," + right).orElseThrow();
        return "SELECT " + key + "," + columns + " FROM " + owner + "." + table
                + " AS OF SCN " + scn(plan.sourceSnapshotScn())
                + " WHERE " + (hasLowerBoundary ? key + ">? AND " : "") + key + "<=?"
                + " ORDER BY " + key;
    }

    private Row canonicalRow(ResultSet resultSet, ResultSetMetaData metadata) throws SQLException {
        BigDecimal key = resultSet.getBigDecimal(1);
        if (key == null) throw new SQLException("Range key cannot be null.");
        List<Cell> cells = new ArrayList<>();
        long bytes = canonicalBytes("NUMBER", key.toPlainString());
        for (int index = 2; index <= metadata.getColumnCount(); index++) {
            Cell cell = canonicalCell(resultSet, metadata, index);
            cells.add(cell);
            bytes = Math.addExact(bytes, canonicalBytes(cell.oracleType(), cell.canonicalValue()));
        }
        return new Row(key, cells, bytes);
    }

    private Cell canonicalCell(ResultSet resultSet, ResultSetMetaData metadata, int index)
            throws SQLException {
        int type = metadata.getColumnType(index);
        return switch (type) {
            case Types.NUMERIC, Types.DECIMAL, Types.INTEGER, Types.BIGINT,
                    Types.SMALLINT, Types.FLOAT, Types.DOUBLE, Types.REAL -> {
                BigDecimal value = resultSet.getBigDecimal(index);
                yield new Cell("NUMBER", value == null ? null : value.toPlainString());
            }
            case Types.CHAR, Types.VARCHAR, Types.NCHAR, Types.NVARCHAR ->
                    new Cell("VARCHAR2", resultSet.getString(index));
            case Types.DATE, Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> {
                var value = resultSet.getTimestamp(index);
                yield new Cell("TIMESTAMP", value == null ? null
                        : DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
                                value.toInstant().atOffset(ZoneOffset.UTC)));
            }
            default -> throw new SQLException("Unsupported Oracle transfer type: " + type);
        };
    }

    private Batch batch(List<Row> rows, long bytes) {
        List<Row> immutable = List.copyOf(rows);
        MessageDigest digest = digest();
        frame(digest, "AKIS_RANGE_BATCH/1");
        for (Row row : immutable) {
            frame(digest, row.key().toPlainString());
            for (Cell cell : row.cells()) {
                frame(digest, cell.oracleType());
                frame(digest, cell.canonicalValue());
            }
        }
        return new Batch(immutable, immutable.getLast().key(), bytes,
                HexFormat.of().formatHex(digest.digest()));
    }

    private Batch emptyBatch(BigDecimal upperBoundary) {
        MessageDigest digest = digest();
        frame(digest, "AKIS_RANGE_BATCH/1");
        frame(digest, "EMPTY");
        frame(digest, upperBoundary.toPlainString());
        return new Batch(List.of(), upperBoundary, 0,
                HexFormat.of().formatHex(digest.digest()));
    }

    private void frame(MessageDigest digest, String value) {
        byte[] bytes = value == null ? new byte[0]
                : value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(4)
                .putInt(value == null ? -1 : bytes.length).array());
        digest.update(bytes);
    }

    private long canonicalBytes(String type, String value) {
        long valueBytes = value == null ? 1 : value.getBytes(StandardCharsets.UTF_8).length;
        return Math.addExact(type.length() + 2L, valueBytes);
    }

    private String identifier(String value) {
        String normalized = value == null ? "" : value.toUpperCase(Locale.ROOT);
        if (!IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Oracle identifier is outside the transfer contract.");
        }
        return normalized;
    }

    private String scn(String value) {
        if (value == null || !value.matches("[0-9]{1,40}")) {
            throw new IllegalArgumentException("Oracle SCN is invalid.");
        }
        return value;
    }

    private MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}

final class OracleRangeReadException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String code;
    OracleRangeReadException(String code, Throwable cause) { super(code, cause); this.code = code; }
    String code() { return code; }
}
