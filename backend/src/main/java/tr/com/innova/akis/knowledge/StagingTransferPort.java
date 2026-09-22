package tr.com.innova.akis.knowledge;

import java.sql.Connection;
import java.util.List;

/**
 * Technology port for the LKM transfer step: stream the source query into the work table and return the seal
 * (rows, bytes, payload hash). The Oracle adapter is {@link JdbcStagingTransfer} (prepared batches); a PostgreSQL adapter
 * streams the same rows through COPY. The value types stay on {@link JdbcStagingTransfer} until a second adapter exists.
 */
public interface StagingTransferPort {
    JdbcStagingTransfer.Result transfer(Connection source, Connection stage, JdbcStagingTransfer.Table from, JdbcStagingTransfer.Table to,
            List<JdbcStagingTransfer.Column> columns, StagedMappingDefinition.Options options, int timeoutSeconds, Runnable checkpoint,
            JdbcTransactionBoundary transaction, JdbcStagingTransfer.QueryOptions queryOptions);
}
