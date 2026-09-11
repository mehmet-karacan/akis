package tr.com.innova.akis.execution;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

/** Reads only the direct columns authorized by a verified bounded pilot plan. */
@Component
final class JdbcOraclePilotSourceReader {

    private final OraclePilotPayloadCodec payloadCodec;

    JdbcOraclePilotSourceReader() {
        this(new OraclePilotPayloadCodec());
    }

    JdbcOraclePilotSourceReader(OraclePilotPayloadCodec payloadCodec) {
        this.payloadCodec = java.util.Objects.requireNonNull(
                payloadCodec, "Payload codec is required.");
    }

    OraclePilotBatch read(Connection connection, PilotRuntimePlan plan) {
        OraclePilotSqlContract.ValidatedPlan safePlan = OraclePilotSqlContract.validate(plan);
        ensureOpen(connection);
        int queryLimit = safePlan.maximumSourceRows() + 1;
        String sql = "SELECT " + OraclePilotSqlContract.quotedColumns(safePlan.sourceColumns())
                + " FROM " + OraclePilotSqlContract.qualified(
                        safePlan.sourceOwner(), safePlan.sourceObject())
                + " FETCH FIRST " + queryLimit + " ROWS ONLY";

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setMaxRows(queryLimit);
            statement.setFetchSize(Math.min(queryLimit, 250));
            try (ResultSet resultSet = statement.executeQuery()) {
                List<OraclePilotColumn> columns = payloadCodec.columns(
                        resultSet.getMetaData(), safePlan.sourceColumns());
                OraclePilotPayloadCodec.PayloadBudget budget = payloadCodec.budget(
                        safePlan.runtimePlanHash(), columns);
                List<List<OraclePilotCell>> rows = new ArrayList<>();
                while (resultSet.next()) {
                    if (rows.size() == safePlan.maximumSourceRows()) {
                        throw new OraclePilotDataException(
                                OraclePilotDataException.Failure.SOURCE_ROW_LIMIT_EXCEEDED,
                                "The Oracle pilot source row limit was exceeded.");
                    }
                    List<OraclePilotCell> row = new ArrayList<>(columns.size());
                    for (int index = 1; index <= columns.size(); index++) {
                        row.add(payloadCodec.read(
                                resultSet, index, columns.get(index - 1).type()));
                    }
                    budget.accept(row);
                    rows.add(row);
                }
                return payloadCodec.batch(safePlan.runtimePlanHash(), columns, rows);
            }
        }
        catch (OraclePilotDataException exception) {
            throw exception;
        }
        catch (SQLException exception) {
            throw sqlFailure(
                    OraclePilotDataException.Failure.SOURCE_READ_FAILED,
                    "The Oracle pilot source read failed.", exception);
        }
    }

    private static void ensureOpen(Connection connection) {
        try {
            if (connection == null || connection.isClosed()) {
                throw new OraclePilotDataException(
                        OraclePilotDataException.Failure.INVALID_CONNECTION,
                        "The Oracle pilot source connection is not open.");
            }
        }
        catch (SQLException exception) {
            throw sqlFailure(
                    OraclePilotDataException.Failure.INVALID_CONNECTION,
                    "The Oracle pilot source connection could not be verified.", exception);
        }
    }

    private static OraclePilotDataException sqlFailure(
            OraclePilotDataException.Failure failure,
            String message,
            SQLException exception) {
        return new OraclePilotDataException(
                failure, message, exception.getSQLState(), exception.getErrorCode());
    }
}
