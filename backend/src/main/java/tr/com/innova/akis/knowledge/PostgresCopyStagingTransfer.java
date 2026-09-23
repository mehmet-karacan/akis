package tr.com.innova.akis.knowledge;

import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyManager;
import tr.com.innova.akis.knowledge.JdbcStagingTransfer.Column;
import tr.com.innova.akis.knowledge.JdbcStagingTransfer.QueryOptions;
import tr.com.innova.akis.knowledge.JdbcStagingTransfer.Result;
import tr.com.innova.akis.knowledge.JdbcStagingTransfer.Table;
import tr.com.innova.akis.knowledge.JdbcStagingTransfer.TransferFailure;

/**
 * PostgreSQL adapter of {@link StagingTransferPort}: streams the Oracle source cursor into the PostgreSQL work table with
 * {@code COPY ... FROM STDIN (FORMAT csv)} in bounded chunks. Chunks share the Oracle adapter's limits (batchRows,
 * 16 MB buffer), commit through the same {@link JdbcTransactionBoundary} and feed the same seal digest, so a COPY-loaded
 * work table seals identically to a JDBC-loaded one. CSV encoding is done here (quote everything textual, NULL as an
 * unquoted {@code \N}) so no value can be mistaken for a delimiter or a null marker.
 */
public final class PostgresCopyStagingTransfer implements StagingTransferPort {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(PostgresCopyStagingTransfer.class);
    private static final String NULL_MARKER = "\\N";
    private final boolean verifySourceMetadata;

    public PostgresCopyStagingTransfer() { this(true); }

    /** {@code false} only for tests whose source is a stand-in (not Oracle) table; production always verifies. */
    PostgresCopyStagingTransfer(boolean verifySourceMetadata) { this.verifySourceMetadata = verifySourceMetadata; }

    @Override
    public Result transfer(Connection source, Connection stage, Table from, Table to, List<Column> columns,
            StagedMappingDefinition.Options options, int timeoutSeconds, Runnable checkpoint,
            JdbcTransactionBoundary transaction, QueryOptions queryOptions) {
        Objects.requireNonNull(transaction); Objects.requireNonNull(checkpoint); Objects.requireNonNull(options); Objects.requireNonNull(queryOptions);
        columns = List.copyOf(columns);
        if (columns.isEmpty() || columns.size() > 256 || timeoutSeconds < 1 || timeoutSeconds > 3600
                || options.batchRows() < 1 || options.batchRows() > 5000 || options.fetchRows() < 1 || options.fetchRows() > 5000
                || options.maxRows() < 1 || options.maxBytes() < 1 || source == stage)
            throw new IllegalArgumentException("Aktarım sözleşmesi geçersiz.");
        long rows = 0, bytes = 0, batchBytes = 0;
        int pending = 0;
        boolean committing = false;
        MessageDigest digest = JdbcStagingTransfer.sealDigest(columns);
        JdbcStagingTransfer.SourceQuery query = JdbcStagingTransfer.sourceQuery(from, columns, queryOptions);
        String copy = "COPY " + to.sql() + " (" + String.join(",", columns.stream().map(c -> quote(c.stage())).toList())
                + ") FROM STDIN WITH (FORMAT csv, NULL '" + NULL_MARKER + "', ENCODING 'UTF8')";
        StringBuilder buffer = new StringBuilder();
        try {
            if (stage.getAutoCommit()) throw new IllegalArgumentException("Stage bağlantısında autocommit kapalı olmalıdır.");
            CopyManager copyManager = stage.unwrap(PGConnection.class).getCopyAPI();
            checkpoint.run();
            try (PreparedStatement read = source.prepareStatement(query.select())) {
                for (int i = 0; i < query.parameters().size(); i++) {
                    Object value = query.parameters().get(i);
                    if (value instanceof BigDecimal number) read.setBigDecimal(i + 1, number); else read.setString(i + 1, (String) value);
                }
                read.setFetchSize(options.fetchRows()); read.setQueryTimeout(timeoutSeconds);
                try (ResultSet cursor = read.executeQuery()) {
                    if (verifySourceMetadata) JdbcStagingTransfer.verifySourceMetadata(cursor.getMetaData(), columns);
                    while (cursor.next()) {
                        if (rows >= options.maxRows()) throw new TransferFailure("Kaynak satır kotası aşıldı; stage mühürlenmedi.", false);
                        Object[] values = new Object[columns.size()];
                        long rowBytes = 0;
                        for (int i = 0; i < columns.size(); i++) {
                            values[i] = JdbcStagingTransfer.readCell(cursor, i + 1, columns.get(i).type());
                            String value = JdbcStagingTransfer.canonicalText(values[i]);
                            long cellBytes = value == null ? 4 : 4L + value.getBytes(StandardCharsets.UTF_8).length;
                            if (cellBytes > 1_048_576) throw new TransferFailure("Tek hücre sınırı aşıldı.", false);
                            rowBytes += cellBytes;
                        }
                        if (rowBytes > JdbcStagingTransfer.BUFFER_BYTES || bytes > options.maxBytes() - rowBytes) throw new TransferFailure("Aktarım byte kotası aşıldı; stage mühürlenmedi.", false);
                        if (pending > 0 && batchBytes + rowBytes > JdbcStagingTransfer.BUFFER_BYTES) {
                            checkpoint.run(); flush(copyManager, copy, buffer, pending); checkpoint.run(); committing = true; transaction.commit(); committing = false;
                            pending = 0; batchBytes = 0;
                        }
                        for (int i = 0; i < columns.size(); i++) {
                            if (columns.get(i).encrypted() && values[i] != null) values[i] = tr.com.innova.akis.security.DataProtectionCipher.encrypt(String.valueOf(values[i]));
                            String canonical = JdbcStagingTransfer.canonicalText(values[i]);
                            if (i > 0) buffer.append(',');
                            appendCsv(buffer, values[i], canonical);
                            JdbcStagingTransfer.frameValue(digest, canonical);
                        }
                        buffer.append('\n');
                        rows++; bytes += rowBytes; batchBytes += rowBytes; pending++;
                        if (pending == options.batchRows()) {
                            checkpoint.run(); flush(copyManager, copy, buffer, pending); checkpoint.run(); committing = true; transaction.commit(); committing = false;
                            pending = 0; batchBytes = 0;
                        }
                    }
                }
                if (rows == 0 && !options.allowEmptySource()) throw new TransferFailure("Boş kaynakta hedef yenileme kapalı.", false);
                if (pending > 0) { checkpoint.run(); flush(copyManager, copy, buffer, pending); checkpoint.run(); committing = true; transaction.commit(); committing = false; }
            }
            checkpoint.run();
            return new Result(rows, bytes, HexFormat.of().formatHex(digest.digest()));
        } catch (SQLException | java.io.IOException ex) {
            try { transaction.rollback(); } catch (SQLException | RuntimeException ignored) { }
            LOG.warn("COPY transfer into {} failed after {} row(s): {}", to.sql(), rows, ex.toString());
            throw new TransferFailure(committing ? "Stage commit sonucu belirsiz; otomatik tekrar yasak." : "Stage aktarımı tamamlanamadı; hedef değiştirilmedi.", committing);
        } catch (RuntimeException ex) {
            try { transaction.rollback(); } catch (SQLException | RuntimeException ignored) { }
            if (committing) throw new TransferFailure("Stage commit sonucu belirsiz; otomatik tekrar yasak.", true);
            throw ex;
        }
    }

    private static void flush(CopyManager copyManager, String copy, StringBuilder buffer, int expected) throws SQLException, java.io.IOException {
        long copied = copyManager.copyIn(copy, new StringReader(buffer.toString()));
        buffer.setLength(0);
        if (copied != expected) throw new TransferFailure("Stage satır sayısı kesin doğrulanamadı.", false);
    }

    /** Numbers and timestamps are emitted bare (PostgreSQL parses the canonical text); anything textual is quoted. */
    private static void appendCsv(StringBuilder buffer, Object value, String canonical) {
        if (value == null) { buffer.append(NULL_MARKER); return; }
        if (value instanceof BigDecimal || value instanceof Timestamp) { buffer.append(canonical); return; }
        buffer.append('"');
        for (int i = 0; i < canonical.length(); i++) {
            char c = canonical.charAt(i);
            if (c == '"') buffer.append('"');
            buffer.append(c);
        }
        buffer.append('"');
    }

    private static String quote(String name) { return "\"" + StagedMappingDefinition.identifier(name) + "\""; }
}
