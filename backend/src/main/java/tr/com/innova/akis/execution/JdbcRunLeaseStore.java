package tr.com.innova.akis.execution;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import tr.com.innova.akis.execution.RunLeasePort.ClaimedRun;
import tr.com.innova.akis.execution.RunLeasePort.HeartbeatResult;
import tr.com.innova.akis.execution.RunLeasePort.RunLeaseToken;
import tr.com.innova.akis.execution.RunLeasePort.TargetFenceToken;
import tr.com.innova.akis.execution.RunLeasePort.WorkerIdentity;

@Repository
public class JdbcRunLeaseStore implements RunLeasePort {

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    private static final int MINIMUM_LEASE_SECONDS = 30;
    private static final int MAXIMUM_LEASE_SECONDS = 300;

    private final JdbcClient jdbc;

    public JdbcRunLeaseStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public Optional<ClaimedRun> claimForPreflight(WorkerIdentity worker, Duration lease) {
        WorkerIdentity safeWorker = worker(worker);
        int leaseSeconds = leaseSeconds(lease);
        return jdbc.sql("""
                        select calistirma_uuid, nesil_no, yayin_ozeti,
                               plan_ozeti, kiralama_bitis_zamani
                          from entegrasyon.calistirma_sahiplen(
                               :profileUuid, :workerReference, :leaseSeconds)
                        """)
                .param("profileUuid", safeWorker.profileUuid())
                .param("workerReference", safeWorker.reference())
                .param("leaseSeconds", leaseSeconds)
                .query((rs, rowNum) -> mapClaim(rs, safeWorker.reference()))
                .optional();
    }

    @Override
    @Transactional
    public HeartbeatResult heartbeat(RunLeaseToken token, Duration lease) {
        RunLeaseToken safeToken = token(token);
        int leaseSeconds = leaseSeconds(lease);
        Boolean accepted = jdbc.sql("""
                        select entegrasyon.calistirma_yasam_sinyali(
                            :runUuid, :workerReference, :generation, :leaseSeconds)
                        """)
                .param("runUuid", safeToken.runUuid())
                .param("workerReference", safeToken.workerReference())
                .param("generation", safeToken.generation())
                .param("leaseSeconds", leaseSeconds)
                .query(Boolean.class)
                .single();
        if (!Boolean.TRUE.equals(accepted)) {
            return HeartbeatResult.rejected();
        }
        OffsetDateTime refreshedDeadline = jdbc.sql("""
                        select cd.kiralama_bitis_zamani
                          from entegrasyon.calistirma_durumu cd
                          join entegrasyon.calistirma c
                            on c.proje_id = cd.proje_id and c.id = cd.calistirma_id
                         where c.uuid = :runUuid
                           and cd.isleyici_referansi = :workerReference
                           and cd.nesil_no = :generation
                        """)
                .param("runUuid", safeToken.runUuid())
                .param("workerReference", safeToken.workerReference())
                .param("generation", safeToken.generation())
                .query(OffsetDateTime.class)
                .single();
        return HeartbeatResult.accepted(new RunLeaseToken(
                safeToken.runUuid(), safeToken.workerReference(),
                safeToken.generation(), refreshedDeadline));
    }

    @Override
    @Transactional
    public TargetFenceToken acquireTarget(
            RunLeaseToken token, String canonicalTargetHash, int identityVersion) {
        RunLeaseToken safeToken = token(token);
        if (canonicalTargetHash == null || !SHA_256.matcher(canonicalTargetHash).matches()) {
            throw new IllegalArgumentException("Canonical target hash must be lowercase SHA-256.");
        }
        if (identityVersion <= 0) {
            throw new IllegalArgumentException("Target identity version must be positive.");
        }
        return jdbc.sql("""
                        select hedef_kaynagi_uuid, hedef_nesil_no
                          from entegrasyon.hedef_kaynagi_sahiplen(
                               :runUuid, :workerReference, :runGeneration,
                               :targetHash, :identityVersion)
                        """)
                .param("runUuid", safeToken.runUuid())
                .param("workerReference", safeToken.workerReference())
                .param("runGeneration", safeToken.generation())
                .param("targetHash", canonicalTargetHash)
                .param("identityVersion", identityVersion)
                .query((rs, rowNum) -> new TargetFenceToken(
                        safeToken.runUuid(), safeToken.workerReference(),
                        safeToken.generation(),
                        rs.getObject("hedef_kaynagi_uuid", UUID.class),
                        rs.getLong("hedef_nesil_no")))
                .single();
    }

    private ClaimedRun mapClaim(ResultSet rs, String workerReference) throws SQLException {
        UUID runUuid = rs.getObject("calistirma_uuid", UUID.class);
        long generation = rs.getLong("nesil_no");
        OffsetDateTime deadline = rs.getObject(
                "kiralama_bitis_zamani", OffsetDateTime.class);
        RunLeaseToken token = new RunLeaseToken(
                runUuid, workerReference, generation, deadline);
        return new ClaimedRun(
                token, rs.getString("yayin_ozeti"), rs.getString("plan_ozeti"));
    }

    private WorkerIdentity worker(WorkerIdentity worker) {
        if (worker == null || worker.profileUuid() == null
                || worker.reference() == null || worker.reference().isBlank()) {
            throw new IllegalArgumentException("Worker identity and profile UUID are required.");
        }
        String reference = worker.reference().trim();
        if (reference.length() > 200) {
            throw new IllegalArgumentException("Worker reference cannot exceed 200 characters.");
        }
        return new WorkerIdentity(reference, worker.profileUuid());
    }

    private RunLeaseToken token(RunLeaseToken token) {
        if (token == null || token.runUuid() == null
                || token.workerReference() == null || token.workerReference().isBlank()
                || token.generation() <= 0 || token.leaseDeadline() == null) {
            throw new IllegalArgumentException("A complete run lease token is required.");
        }
        return token;
    }

    private int leaseSeconds(Duration lease) {
        if (lease == null || lease.isNegative() || lease.isZero()
                || lease.getNano() != 0
                || lease.getSeconds() < MINIMUM_LEASE_SECONDS
                || lease.getSeconds() > MAXIMUM_LEASE_SECONDS) {
            throw new IllegalArgumentException("Lease must be 30-300 whole seconds.");
        }
        return Math.toIntExact(lease.getSeconds());
    }
}
