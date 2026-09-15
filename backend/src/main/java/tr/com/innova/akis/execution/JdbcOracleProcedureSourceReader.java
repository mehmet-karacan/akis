package tr.com.innova.akis.execution;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import tr.com.innova.akis.execution.RuntimeOracleConnectionProvider.RuntimeOracleSession;

/** Executes one already-verified, bounded Procedure source SELECT. */
@Component
final class JdbcOracleProcedureSourceReader {

    private final OraclePilotPayloadCodec payloadCodec;

    JdbcOracleProcedureSourceReader() {
        this(new OraclePilotPayloadCodec());
    }

    JdbcOracleProcedureSourceReader(OraclePilotPayloadCodec payloadCodec) {
        this.payloadCodec = java.util.Objects.requireNonNull(payloadCodec);
    }

    OraclePilotBatch read(
            RuntimeOracleSession session,
            ProcedureRuntimePlan plan,
            ProcedureRuntimePlan.Task task,
            ProcedureRuntimePlan.TaskBinding binding) {
        return read(session, plan, task, binding, new ProcedureVariableContext());
    }

    OraclePilotBatch read(RuntimeOracleSession session, ProcedureRuntimePlan plan,
            ProcedureRuntimePlan.Task task, ProcedureRuntimePlan.TaskBinding binding,
            ProcedureVariableContext variables) {
        ProcedureOracleSourceSqlContract.ValidatedSource source =
                ProcedureOracleSourceSqlContract.validate(plan, task, binding);
        int queryLimit = source.maximumRows() + 1;
        try (PreparedStatement statement = session.applyQueryTimeout(
                session.connection().prepareStatement(source.sql()))) {
            ProcedureParameterBinder.bind(statement, task, variables, binding.connectionVersionUuid());
            statement.setMaxRows(queryLimit);
            statement.setFetchSize(Math.min(queryLimit, 250));
            try (ResultSet rows = statement.executeQuery()) {
                List<OraclePilotColumn> columns = payloadCodec.columns(
                        rows.getMetaData(), source.columns());
                OraclePilotPayloadCodec.PayloadBudget budget = payloadCodec.budget(
                        source.runtimePlanHash(), columns);
                List<List<OraclePilotCell>> values = new ArrayList<>();
                while (rows.next()) {
                    if (values.size() == source.maximumRows()) {
                        throw new OraclePilotDataException(
                                OraclePilotDataException.Failure.SOURCE_ROW_LIMIT_EXCEEDED,
                                "The Procedure source row limit was exceeded.");
                    }
                    List<OraclePilotCell> row = new ArrayList<>(columns.size());
                    for (int index = 1; index <= columns.size(); index++) {
                        row.add(payloadCodec.read(
                                rows, index, columns.get(index - 1).type()));
                    }
                    budget.accept(row);
                    values.add(row);
                }
                return payloadCodec.batch(source.runtimePlanHash(), columns, values);
            }
        }
        catch (OraclePilotDataException exception) {
            throw exception;
        }
        catch (SQLException exception) {
            throw new OraclePilotDataException(
                    OraclePilotDataException.Failure.SOURCE_READ_FAILED,
                    "The Procedure source read failed.",
                    exception.getSQLState(), exception.getErrorCode());
        }
    }
}
