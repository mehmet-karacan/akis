package tr.com.innova.akis.metadata;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class VariableQueryPolicyTest {
    @Test void acceptsScalarQueriesAndBuiltinsBeyondTheOriginalExample() {
        for (String sql : new String[] { "SELECT SYSDATE - 1 FROM DUAL", "SELECT MAX(BUSINESS_DATE) FROM JOB_DATES",
            "SELECT TO_CHAR(TRUNC(SYSDATE),'YYYY-MM-DD') FROM DUAL", "SELECT COUNT(*) FROM JOBS WHERE STATUS='DONE'",
            "WITH X AS (SELECT 1 VALUE FROM DUAL) SELECT VALUE FROM X", "SELECT CAST(1 AS NUMBER(10,0)) FROM DUAL" }) {
            assertEquals(sql, VariableQueryPolicy.validate(sql));
        }
    }
    @Test void stripsOnlyTheSingleTrailingTerminatorAndAllowsLiteralPunctuation() {
        assertEquals("SELECT 'a;b@c:1' FROM DUAL", VariableQueryPolicy.validate(" SELECT 'a;b@c:1' FROM DUAL; "));
    }
    @Test void rejectsMutationMultipleStatementsAndUserFunctions() {
        for (String sql : new String[] { "DELETE FROM T", "SELECT 1 FROM DUAL; DROP TABLE T", "SELECT F() FROM DUAL",
            "SELECT APP.TO_CHAR(1) FROM DUAL", "SELECT \"F\"(1) FROM DUAL", "WITH FUNCTION f RETURN NUMBER IS BEGIN RETURN 1; END; SELECT f FROM DUAL",
            "SELECT S.NEXTVAL FROM DUAL", "SELECT S.\"NEXTVAL\" FROM DUAL", "SELECT * FROM T FOR UPDATE",
            "SELECT * FROM T@REMOTE", "SELECT :UNKNOWN FROM DUAL", "SELECT /* unterminated", "SELECT 'unterminated" }) {
            assertThrows(ApiException.class, () -> VariableQueryPolicy.validate(sql), sql);
        }
    }
    @Test void commentsCannotHideDangerousFunctionQualification() {
        assertThrows(ApiException.class, () -> VariableQueryPolicy.validate("SELECT APP./*x*/TO_CHAR(1) FROM DUAL"));
        assertThrows(ApiException.class, () -> VariableQueryPolicy.validate("SELECT F /*x*/ (1) FROM DUAL"));
        assertEquals("SELECT 'DELETE' FROM DUAL -- x", VariableQueryPolicy.validate("SELECT 'DELETE' FROM DUAL -- x"));
    }
    @Test void rejectsMissingOrOversizedQuery() {
        assertThrows(ApiException.class, () -> VariableQueryPolicy.validate(null));
        assertThrows(ApiException.class, () -> VariableQueryPolicy.validate(" "));
        assertThrows(ApiException.class, () -> VariableQueryPolicy.validate("SELECT " + "x".repeat(20000)));
    }
}
