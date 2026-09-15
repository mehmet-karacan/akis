package tr.com.innova.akis.publication;

/** Shared policy for immutable publication creation and runtime verification. */
public final class ApprovalPolicyEvaluator {
    public static final int POLICY_VERSION = 1;
    private ApprovalPolicyEvaluator() { }

    public static boolean requiresApproval(String environmentRisk, boolean taskRequiresApproval) {
        return "URETIM".equals(environmentRisk) || taskRequiresApproval;
    }
}
