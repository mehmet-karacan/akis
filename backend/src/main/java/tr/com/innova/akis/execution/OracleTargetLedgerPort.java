package tr.com.innova.akis.execution;

import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import tr.com.innova.akis.execution.PinnedExecutionContextPort.PinnedExecutionContext;
import tr.com.innova.akis.execution.RunLeasePort.TargetFenceToken;

/**
 * Target-local Oracle ledger boundary. A bound session never commits or rolls back;
 * its caller owns the transaction outcome. Prepare and record must be invoked through
 * the same data session so both calls use the same physical JDBC connection. Fence
 * acquisition is deliberately exposed through a different session type and package
 * V2 rejects data preparation until the fence transaction is committed. Reconciliation
 * must use a fresh, independent physical connection so it cannot observe uncommitted
 * evidence through the writer transaction.
 */
interface OracleTargetLedgerPort {

    FenceSession bindFence(Connection connection, TargetLedgerContext context);

    DataLedgerSession bindData(Connection connection, TargetLedgerContext context);

    ReconciliationSession bindReconciliation(
            Connection connection, TargetLedgerContext context);

    interface FenceSession {

        void acquireFence();
    }

    interface DataLedgerSession {

        BatchPreparation prepareBatch(BatchEvidence evidence);

        void recordBatch(BatchPreparation preparation);

        PublishPreparation preparePublish(PublishEvidence evidence);

        void recordPublish(PublishPreparation preparation);
    }

    interface ReconciliationSession {

        Optional<FenceEvidence> readFence();

        Optional<RecordedEvidence> verifyBatch(BatchEvidence evidence);

        Optional<RecordedEvidence> verifyPublish(PublishEvidence evidence);
    }

    record TargetLedgerContext(
            String canonicalTargetHash,
            long fenceToken,
            UUID jobRequestUuid,
            UUID runUuid,
            int attemptNumber,
            String releaseHash,
            String planHash) {

        static TargetLedgerContext from(
                TargetFenceToken target, PinnedExecutionContext execution) {
            if (target == null || execution == null
                    || !target.runUuid().equals(execution.runUuid())) {
                throw new IllegalArgumentException(
                        "Target fence and pinned execution context must identify the same run.");
            }
            return new TargetLedgerContext(
                    target.canonicalTargetHash(), target.targetGeneration(),
                    execution.jobRequestUuid(), execution.runUuid(),
                    execution.attemptNumber(), execution.releaseHash(), execution.planHash());
        }
    }

    record FenceEvidence(
            long fenceToken,
            UUID jobRequestUuid,
            UUID runUuid,
            int attemptNumber,
            String releaseHash,
            String planHash,
            OffsetDateTime updatedAt) {
    }

    record BatchEvidence(
            String stepCode,
            String partitionCode,
            String batchKeyHash,
            long batchNumber,
            String payloadHash,
            long rowCount,
            long byteCount) {
    }

    record PublishEvidence(
            String stepCode,
            String publishKeyHash,
            String stageHash,
            long stageRowCount,
            long publishedRowCount,
            long rejectedRowCount,
            String lowerWatermark,
            String upperWatermark) {
    }

    record BatchPreparation(
            UUID sessionBindingId,
            BatchEvidence evidence,
            String transactionGuard,
            boolean alreadyRecorded) {
    }

    record PublishPreparation(
            UUID sessionBindingId,
            PublishEvidence evidence,
            String transactionGuard,
            boolean alreadyRecorded) {
    }

    record RecordedEvidence(
            UUID runUuid,
            int attemptNumber,
            long fenceToken,
            OffsetDateTime evidenceAt) {
    }
}
