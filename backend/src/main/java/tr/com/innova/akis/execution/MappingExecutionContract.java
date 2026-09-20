package tr.com.innova.akis.execution;

import java.util.List;
import java.util.UUID;

/** Shared physical-schema proof, not a shared limit/strategy or executable capability. */
public interface MappingExecutionContract {
    UUID definitionUuid();
    UUID definitionVersionUuid();
    String releaseHash();
    String runtimePlanHash();
    String scenarioPlanHash();
    PilotRuntimePlan.DatasetBinding source();
    PilotRuntimePlan.DatasetBinding target();
    List<? extends ColumnProjection> columnMappings();

    interface ColumnProjection {
        String targetColumn();
        /** Null for an expression projection; never a fabricated source column. */
        String sourceColumn();
        default tools.jackson.databind.JsonNode expression() { return null; }
    }

    record ExpressionProjection(tools.jackson.databind.JsonNode expression, String targetColumn) implements ColumnProjection {
        public ExpressionProjection { expression = expression.deepCopy(); }
        @Override public tools.jackson.databind.JsonNode expression() { return expression.deepCopy(); }
        @Override public String sourceColumn() { return null; }
    }
}
