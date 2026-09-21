package tr.com.innova.akis.publication;

/** Shared policy for immutable publication creation and runtime verification. */
public final class ApprovalPolicyEvaluator {
    public static final int POLICY_VERSION = 1;
    private ApprovalPolicyEvaluator() { }

    /**
     * Production always needs a decision. A task that flags itself (TRUNCATE, DDL, stats) only needs one on a
     * high-risk environment; low/medium-risk environments (development, test) run destructive steps without a gate.
     */
    public static boolean requiresApproval(String environmentRisk, boolean taskRequiresApproval) {
        return "URETIM".equals(environmentRisk) || "YUKSEK".equals(environmentRisk) && taskRequiresApproval;
    }
}
