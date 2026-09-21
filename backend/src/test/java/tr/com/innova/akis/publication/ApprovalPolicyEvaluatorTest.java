package tr.com.innova.akis.publication;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ApprovalPolicyEvaluatorTest {
    @Test
    void productionAlwaysAndTaskRiskOnlyOnHighRiskEnvironmentsRequireApproval() {
        for (String environment : new String[] { "DUSUK", "ORTA", "YUKSEK", "URETIM" }) {
            for (boolean task : new boolean[] { false, true }) {
                assertEquals(environment.equals("URETIM") || task && environment.equals("YUKSEK"),
                        ApprovalPolicyEvaluator.requiresApproval(environment, task));
            }
        }
    }
}
