package tr.com.innova.akis.execution;

import java.util.Objects;
import java.util.regex.Pattern;

import tr.com.innova.akis.execution.RunLeasePort.RunLeaseToken;
import tr.com.innova.akis.execution.RunLeasePort.TargetFenceToken;

/**
 * PostgreSQL mutation boundary for one actively fenced pilot execution.
 *
 * <p>Every method is one independent transaction. If the caller loses the
 * acknowledgement (the method throws), it may retry the exact same method once
 * with the exact same token and evidence. A different tuple is never a retry.
 */
interface RunExecutionTransitionPort {

    MutationResult completePreflight(ActiveExecutionToken token);

    MutationResult beginPublish(
            ActiveExecutionToken token, PublishIntentEvidence evidence);

    /** Ends a rejected preflight before a target fence has been acquired. */
    MutationResult failPreflightSafely(
            RunLeaseToken token, String safeErrorCode);

    MutationResult failSafely(ActiveExecutionToken token, String safeErrorCode);

    MutationResult markOutcomeUnknown(ActiveExecutionToken token);

    MutationResult completeSuccessfully(
            ActiveExecutionToken token, PublishIntentEvidence evidence);

    enum MutationOutcome {
        ACCEPTED,
        REJECTED_FAIL_CLOSED
    }

    record MutationResult(MutationOutcome outcome) {

        static MutationResult accepted() {
            return new MutationResult(MutationOutcome.ACCEPTED);
        }

        static MutationResult rejected() {
            return new MutationResult(MutationOutcome.REJECTED_FAIL_CLOSED);
        }
    }

    /** Keeps the run lease and target fence inseparable at every mutation. */
    record ActiveExecutionToken(RunLeaseToken run, TargetFenceToken target) {

        public ActiveExecutionToken {
            if (run == null || target == null || run.runUuid() == null
                    || run.workerReference() == null || run.workerReference().isBlank()
                    || run.generation() <= 0 || run.leaseDeadline() == null
                    || target.runUuid() == null || target.workerReference() == null
                    || target.workerReference().isBlank() || target.runGeneration() <= 0
                    || target.targetResourceUuid() == null
                    || target.targetGeneration() <= 0
                    || !Objects.equals(run.runUuid(), target.runUuid())
                    || !Objects.equals(run.workerReference(), target.workerReference())
                    || run.generation() != target.runGeneration()) {
                throw new IllegalArgumentException(
                        "Run lease and target fence must form one exact active token.");
            }
        }
    }

    /** Exact immutable evidence reused by publish intent and success checkpoint. */
    record PublishIntentEvidence(
            String runtimePlanHash,
            String publishKeyHash,
            String payloadHash,
            long rowCount,
            long byteCount) {

        private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

        public PublishIntentEvidence {
            if (!hash(runtimePlanHash) || !hash(publishKeyHash) || !hash(payloadHash)
                    || rowCount < 0 || byteCount < 0) {
                throw new IllegalArgumentException(
                        "Publish intent evidence must be canonical.");
            }
        }

        private static boolean hash(String value) {
            return value != null && HASH.matcher(value).matches();
        }
    }
}
