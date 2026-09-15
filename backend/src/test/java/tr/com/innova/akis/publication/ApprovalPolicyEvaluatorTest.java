package tr.com.innova.akis.publication;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ApprovalPolicyEvaluatorTest {
    @Test
    void productionOrTaskRiskAlwaysRequiresApproval() {
        for (String environment : new String[] { "GELISTIRME", "TEST", "URETIM" }) {
            for (boolean task : new boolean[] { false, true }) {
                assertEquals(task || environment.equals("URETIM"),
                        ApprovalPolicyEvaluator.requiresApproval(environment, task));
            }
        }
    }
}
