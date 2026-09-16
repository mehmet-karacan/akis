package tr.com.innova.akis.execution;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import tr.com.innova.akis.execution.ChunkManifestStore.Intent;
import tr.com.innova.akis.execution.OracleChunkWriteFacade.Outcome;
import tr.com.innova.akis.execution.OracleTargetLedgerPort.BatchEvidence;

/** Durable one-writer range loop. It advances only after target-local evidence. */
final class ChunkExecutionCoordinator {

    private final ChunkManifestStore manifests;

    ChunkExecutionCoordinator(ChunkManifestStore manifests) {
        this.manifests = Objects.requireNonNull(manifests);
    }

    StreamingRowReader.ReadResult execute(
            StreamingRowReader reader,
            StreamingRowReader.Range range,
            Context context,
            ChunkWriter writer,
            ReceiptVerifier receiptVerifier) {
        Objects.requireNonNull(reader); Objects.requireNonNull(range);
        Objects.requireNonNull(context); Objects.requireNonNull(writer);
        Objects.requireNonNull(receiptVerifier);
        AtomicLong sequence = new AtomicLong(context.firstSequence());
        AtomicReference<BigDecimal> lower = new AtomicReference<>(range.lowerExclusive());
        return reader.read(range, batch -> {
            long current = sequence.getAndIncrement();
            BigDecimal upper = batch.lastKey();
            String workUnitKey = hash("AKIS_CHUNK/1|" + context.jobRequestUuid() + "|"
                    + context.runtimePlanHash() + "|" + context.inputHash() + "|"
                    + context.workspaceUuid() + "|" + context.stepCode() + "|"
                    + context.partitionCode() + "|" + decimal(lower.get()) + "|"
                    + decimal(upper) + "|" + batch.payloadHash());
            Intent requested = new Intent(context.projectUuid(), context.jobRequestUuid(),
                    context.stepCode(), context.partitionUuid(), context.workspaceUuid(), current,
                    workUnitKey, context.runtimePlanHash(), context.inputHash(), lower.get(), upper,
                    upper, batch.payloadHash(), batch.rows().size(), batch.canonicalBytes());
            var recorded = manifests.record(requested);
            if ("COMMIT_CONFIRMED".equals(recorded.status())) {
                BatchEvidence evidence = evidence(context, workUnitKey, current, batch);
                if (recorded.targetReceiptReference() == null
                        || !receiptVerifier.verify(evidence, recorded.targetReceiptReference())) {
                    throw new ChunkReconciliationRequiredException(
                            "TARGET_RECEIPT_NOT_VERIFIED");
                }
                lower.set(upper);
                return true;
            }
            if (!"INTENT_RECORDED".equals(recorded.status())
                    && !"ROLLBACK_CONFIRMED".equals(recorded.status())) {
                throw new ChunkReconciliationRequiredException(recorded.status());
            }
            if (!manifests.markDispatched(recorded.chunkUuid(), context.runUuid(),
                    context.runGeneration(), context.workerReference())) {
                throw new ChunkDispatchRejectedException();
            }
            BatchEvidence evidence = evidence(context, workUnitKey, current, batch);
            OracleChunkWriteFacade.Result result = writer.write(evidence, batch);
            String receipt = "oracle-batch:" + workUnitKey;
            if (result.outcome() == Outcome.COMMIT_CONFIRMED
                    || result.outcome() == Outcome.ALREADY_RECORDED) {
                if (!manifests.confirm(recorded.chunkUuid(), receipt, upper)) {
                    throw new ChunkCheckpointUnconfirmedException();
                }
                lower.set(upper);
                return true;
            }
            if (result.outcome() == Outcome.ROLLBACK_CONFIRMED) {
                if (!manifests.rollback(recorded.chunkUuid(), "CHUNK_ROLLED_BACK")) {
                    throw new ChunkCheckpointUnconfirmedException();
                }
                throw new ChunkRolledBackException();
            }
            if (!manifests.unknown(recorded.chunkUuid(), "CHUNK_COMMIT_UNKNOWN")) {
                throw new ChunkCheckpointUnconfirmedException();
            }
            throw new ChunkReconciliationRequiredException("OUTCOME_UNKNOWN");
        });
    }

    record Context(
            UUID projectUuid,
            UUID jobRequestUuid,
            UUID runUuid,
            long runGeneration,
            String workerReference,
            String stepCode,
            UUID partitionUuid,
            String partitionCode,
            UUID workspaceUuid,
            String runtimePlanHash,
            String inputHash,
            long firstSequence) {
        Context {
            if (projectUuid == null || jobRequestUuid == null || runUuid == null
                    || runGeneration < 1 || workerReference == null || workerReference.isBlank()
                    || stepCode == null || !stepCode.matches("[A-Z][A-Z0-9_]{0,99}")
                    || partitionUuid == null || partitionCode == null
                    || !partitionCode.matches("[A-Z][A-Z0-9_]{0,79}")
                    || workspaceUuid == null || !isHash(runtimePlanHash) || !isHash(inputHash)
                    || firstSequence < 1) throw new IllegalArgumentException("Chunk context is incomplete.");
        }
    }

    @FunctionalInterface
    interface ChunkWriter {
        OracleChunkWriteFacade.Result write(
                BatchEvidence evidence, StreamingRowReader.Batch batch);
    }

    @FunctionalInterface
    interface ReceiptVerifier {
        boolean verify(BatchEvidence evidence, String targetReceiptReference);
    }

    private static BatchEvidence evidence(Context context, String workUnitKey, long sequence,
            StreamingRowReader.Batch batch) {
        return new BatchEvidence(context.stepCode(), context.partitionCode(), workUnitKey,
                sequence, batch.payloadHash(), batch.rows().size(), batch.canonicalBytes());
    }

    private static String decimal(BigDecimal value) {
        return value == null ? "-INF" : value.toPlainString();
    }
    private static boolean isHash(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}

final class ChunkDispatchRejectedException extends RuntimeException { private static final long serialVersionUID = 1L; }
final class ChunkCheckpointUnconfirmedException extends RuntimeException { private static final long serialVersionUID = 1L; }
final class ChunkRolledBackException extends RuntimeException { private static final long serialVersionUID = 1L; }
final class ChunkReconciliationRequiredException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    ChunkReconciliationRequiredException(String state) { super(state); }
}
