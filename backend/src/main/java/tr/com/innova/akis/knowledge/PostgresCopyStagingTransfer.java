package tr.com.innova.akis.knowledge;

import java.io.ByteArrayInputStream;
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
 * {@code COPY ... FROM STDIN (FORMAT csv)} in bounded chunks. Chunks share the JDBC adapter's row limits and
 * resource-mode memory budget, commit through the same {@link JdbcTransactionBoundary} and feed the same seal digest, so a COPY-loaded
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
        var telemetry=new TransferTelemetry(checkpoint);
        var checked=telemetry.checkpoint();
        var budget=new TransferBuffer(options,checked);
        MessageDigest digest = JdbcStagingTransfer.sealDigest(columns);
        JdbcStagingTransfer.SourceQuery query = JdbcStagingTransfer.sourceQuery(from, columns, queryOptions);
        String copy = "COPY " + to.sql() + " (" + String.join(",", columns.stream().map(c -> quote(c.stage())).toList())
                + ") FROM STDIN WITH (FORMAT csv, NULL '" + NULL_MARKER + "', ENCODING 'UTF8')";
        StringBuilder buffer = new StringBuilder();
        try {
            if (stage.getAutoCommit()) throw new IllegalArgumentException("Stage bağlantısında autocommit kapalı olmalıdır.");
            CopyManager copyManager = stage.unwrap(PGConnection.class).getCopyAPI();
            checked.run();
            try (PreparedStatement read = source.prepareStatement(query.select())) {
                for (int i = 0; i < query.parameters().size(); i++) {
                    Object value = query.parameters().get(i);
                    if (value instanceof BigDecimal number) read.setBigDecimal(i + 1, number); else read.setString(i + 1, (String) value);
                }
                int fetchRows=budget.configure(read,columns);
                read.setQueryTimeout(timeoutSeconds);
                long readStarted=System.nanoTime();
                try (ResultSet cursor = read.executeQuery()) {
                    telemetry.query(System.nanoTime()-readStarted);
                    if (verifySourceMetadata) JdbcStagingTransfer.verifySourceMetadata(cursor.getMetaData(), columns);
                    if(options.automatic()) {
                        budget.project(cursor.getMetaData(),columns);
                        fetchRows=budget.fetchRows();cursor.setFetchSize(fetchRows);
                    }
                    telemetry.read(System.nanoTime()-readStarted);
                    telemetry.window(budget.requestedBudget(),budget.effectiveBudget(),budget.readBudget(),budget.memoryBudget(),fetchRows,fetchRows,fetchRows);
                    readStarted=System.nanoTime();
                    while (true) {
                        long fetchStarted=System.nanoTime();boolean hasNext=cursor.next();telemetry.fetch(System.nanoTime()-fetchStarted);
                        if(!hasNext) {telemetry.read(System.nanoTime()-readStarted);break;}
                        if (rows >= options.maxRows()) throw new TransferFailure("Kaynak satır kotası aşıldı; stage mühürlenmedi.", false);
                        long decodeStarted=System.nanoTime(),beforeLob=budget.lobNanos(),beforeCells=budget.lobCells();
                        var row=budget.read(cursor,columns,bytes);
                        telemetry.decode(System.nanoTime()-decodeStarted,budget.lobNanos()-beforeLob,budget.lobCells()-beforeCells);
                        Object[] values=row.values();
                        long rowReadNanos=System.nanoTime()-readStarted;telemetry.read(rowReadNanos);budget.observeRead(rowReadNanos);
                        if (pending > 0 && batchBytes + row.residentBytes() > budget.memoryBudget()) {
                            checked.run();long start=System.nanoTime();flush(copyManager,copy,buffer,pending);telemetry.write(System.nanoTime()-start);
                            checked.run();committing=true;start=System.nanoTime();transaction.commit();telemetry.commit(System.nanoTime()-start);committing=false;
                            telemetry.report(rows,bytes,false);
                            pending = 0; batchBytes = 0;
                        }
                        long encodeStarted=System.nanoTime();
                        for (int i = 0; i < columns.size(); i++) {
                            String canonical = row.canonical()[i];
                            if (i > 0) buffer.append(',');
                            appendCsv(buffer, values[i], canonical);
                            JdbcStagingTransfer.frameValue(digest, canonical);
                        }
                        buffer.append('\n');
                        telemetry.encode(System.nanoTime()-encodeStarted);
                        rows++; bytes += row.logicalBytes(); batchBytes += row.residentBytes(); pending++;
                        if (pending >= options.batchRows() || batchBytes>=budget.memoryBudget()) {
                            checked.run();long start=System.nanoTime();flush(copyManager,copy,buffer,pending);telemetry.write(System.nanoTime()-start);
                            checked.run();committing=true;start=System.nanoTime();transaction.commit();telemetry.commit(System.nanoTime()-start);committing=false;
                            telemetry.report(rows,bytes,false);
                            pending = 0; batchBytes = 0;
                        }
                        if(options.automatic() && budget.fetchRows()!=fetchRows) { fetchRows=budget.fetchRows();cursor.setFetchSize(fetchRows); }
                        telemetry.window(budget.requestedBudget(),budget.effectiveBudget(),budget.readBudget(),budget.memoryBudget(),fetchRows,budget.minFetchRows(),budget.maxFetchRows());
                        readStarted=System.nanoTime();
                    }
                }
                if (rows == 0 && !options.allowEmptySource()) throw new TransferFailure("Boş kaynakta hedef yenileme kapalı.", false);
                if (pending > 0) {
                    checked.run();long start=System.nanoTime();flush(copyManager,copy,buffer,pending);telemetry.write(System.nanoTime()-start);
                    checked.run();committing=true;start=System.nanoTime();transaction.commit();telemetry.commit(System.nanoTime()-start);committing=false;
                }
            }
            checked.run();
            telemetry.report(rows,bytes,true);
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
        // Reader COPY encodes independent char chunks and can split UTF-16 surrogate pairs.
        // Encode the bounded batch once; byte COPY keeps UTF-8 characters intact across network chunks.
        long copied = copyManager.copyIn(copy, new ByteArrayInputStream(buffer.toString().getBytes(StandardCharsets.UTF_8)));
        buffer.setLength(0);
        if (copied != expected) throw new TransferFailure("Stage satır sayısı kesin doğrulanamadı.", false);
    }

    /** Numbers and timestamps are emitted bare (PostgreSQL parses the canonical text); anything textual is quoted. */
    private static void appendCsv(StringBuilder buffer, Object value, String canonical) {
        if (value == null) { buffer.append(NULL_MARKER); return; }
        if (value instanceof BigDecimal || value instanceof Timestamp) { buffer.append(canonical); return; }
        // The seal uses Base64 framing for binary cells; PostgreSQL bytea's input syntax is independently hex.
        // An empty binary cell becomes "\\x", while NULL stays the unquoted NULL_MARKER.
        if (value instanceof byte[] bytes) canonical = "\\x" + HexFormat.of().formatHex(bytes);
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
