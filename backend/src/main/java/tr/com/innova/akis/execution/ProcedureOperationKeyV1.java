package tr.com.innova.akis.execution;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import tr.com.innova.akis.execution.ProcedureExecutionJournalPort.TaskEvidence;
import tr.com.innova.akis.execution.RunExecutionTransitionPort.ActiveExecutionToken;

/** Retry-stable identity for one mutating Procedure task attempt. */
final class ProcedureOperationKeyV1 {

    private static final String DOMAIN = "AKIS_ORACLE_PROCEDURE_OPERATION";
    private static final int VERSION = 1;

    String create(ActiveExecutionToken token, TaskEvidence evidence) {
        if (token == null || evidence == null || evidence.task() == null) {
            throw new IllegalArgumentException("Procedure operation key evidence is required.");
        }
        MessageDigest digest = sha256();
        field(digest, DOMAIN);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(VERSION).array());
        field(digest, token.run().runUuid().toString());
        field(digest, token.run().workerReference());
        number(digest, token.run().generation());
        field(digest, token.target().targetResourceUuid().toString());
        number(digest, token.target().targetGeneration());
        field(digest, token.target().canonicalTargetHash());
        number(digest, token.target().targetIdentityVersion());
        field(digest, evidence.runtimePlanHash());
        number(digest, evidence.taskIndex());
        field(digest, evidence.task().id());
        field(digest, evidence.task().commandHash());
        return HexFormat.of().formatHex(digest.digest());
    }

    private void number(MessageDigest digest, long value) {
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(value).array());
    }

    private void field(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}
