package tr.com.innova.akis.execution;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SqlStatementPolicyTest {
    @Test
    void classifiesDestructiveStatementsWithoutTrustingClientRisk() {
        for (String sql : new String[] {"DROP TABLE T", "DELETE FROM T", "TRUNCATE TABLE T", "BEGIN P(); END;"}) {
            var result = SqlStatementPolicy.inspect("/* note */ " + sql, "TARGET");
            assertEquals("DESTRUCTIVE", result.riskClass());
            assertTrue(result.requiresApproval());
        }
        assertEquals("DML", SqlStatementPolicy.inspect("INSERT INTO T VALUES (:a)", "TARGET").riskClass());
        assertEquals("DDL", SqlStatementPolicy.inspect("ALTER TABLE T ADD X NUMBER", "TARGET").riskClass());
    }

    @Test
    void ignoresLiteralPunctuationButRejectsUnsafeAndMalformedShapes() {
        assertEquals("READ_ONLY", SqlStatementPolicy.inspect("SELECT q'[; ,) :fake]' FROM T", "SOURCE").riskClass());
        for (String sql : new String[] {"SELECT A, FROM T", "SELECT (A FROM T", "SELECT A FROM T; DELETE FROM T", "WITH X AS (SELECT 1 FROM DUAL) SELECT * FROM X"}) {
            // A WITH expression is deliberately outside the bounded runtime profile.
            assertThrows(IllegalArgumentException.class, () -> SqlStatementPolicy.inspect(sql, "SOURCE"));
        }
        assertThrows(IllegalArgumentException.class, () -> SqlStatementPolicy.inspect("DELETE FROM T", "SOURCE"));
    }
}
