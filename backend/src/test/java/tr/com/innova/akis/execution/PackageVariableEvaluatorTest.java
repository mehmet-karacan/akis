package tr.com.innova.akis.execution;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class PackageVariableEvaluatorTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void missingSpecIsTrueAndNullChecksIgnoreTheExpectedValue() {
        assertTrue(PackageVariableEvaluator.evaluate(5L, null));
        assertTrue(PackageVariableEvaluator.evaluate(null, mapper.createObjectNode().put("operator", "IS_NULL")));
        assertFalse(PackageVariableEvaluator.evaluate("x", mapper.createObjectNode().put("operator", "IS_NULL")));
        assertFalse(PackageVariableEvaluator.evaluate(null, mapper.createObjectNode().put("operator", "GREATER").put("value", "1")));
    }

    @Test void numbersCompareNumericallyAndStringsLexically() {
        assertTrue(PackageVariableEvaluator.evaluate(1000L, mapper.createObjectNode().put("operator", "GREATER_OR_EQUAL").put("value", "1000")));
        assertTrue(PackageVariableEvaluator.evaluate(new java.math.BigDecimal("10.5"), mapper.createObjectNode().put("operator", "LESS").put("value", "11")));
        assertFalse(PackageVariableEvaluator.evaluate(9L, mapper.createObjectNode().put("operator", "GREATER").put("value", "10")));
        assertTrue(PackageVariableEvaluator.evaluate("AKTIF", mapper.createObjectNode().put("operator", "EQUALS").put("value", "AKTIF")));
        assertTrue(PackageVariableEvaluator.evaluate("B", mapper.createObjectNode().put("operator", "NOT_EQUALS").put("value", "A")));
        assertTrue(PackageVariableEvaluator.evaluate(Boolean.TRUE, mapper.createObjectNode().put("value", "true")));
    }
}
