package tr.com.innova.akis.knowledge;

import java.sql.*;
import java.util.*;
import static tr.com.innova.akis.knowledge.WorkObjectLifecycle.State;

/** Single-use adapter for the finite interpreter. Publication authority remains in the fenced publisher. */
/**
 * Technology-neutral staged KM state machine (create → transfer → seal → checks → publish). Vendor behaviour lives behind
 * {@link WorkTableManagerPort}, {@link StagingTransferPort} and {@link TargetPublishPort}; this class only orders the steps,
 * re-verifies the work object and records lifecycle transitions.
 */
public final class StagedKmRuntime implements AkisKmInterpreter.Runtime {
    public enum PublishOutcome { COMMITTED, ALREADY_RECORDED, ROLLED_BACK, UNKNOWN }
    public record PublishResult(PublishOutcome outcome, long insertedRows) { }
    /** Live pinned schema, owned object and lease checks supplied by the execution boundary. */
    public interface Guard {
        void preflight();
        void checkpoint();
        void verifyWork(WorkTableManagerPort.Created object);
    }
    public record Contract(WorkObjectStore.Owner owner, AkisKmInterpreter.Plan plan,
            String targetDatabaseIdentity, String targetUser, JdbcStagingTransfer.Table source,
            JdbcStagingTransfer.Table work, List<WorkTableManagerPort.Column> workColumns,
            List<JdbcStagingTransfer.Column> transferColumns, StagedMappingDefinition.Options options,
            JdbcWorkQualityChecks.Contract quality, int timeoutSeconds,WorkObjectStore.WorkArea workArea,
            JdbcStagingTransfer.QueryOptions queryOptions) {
        public Contract(WorkObjectStore.Owner owner, AkisKmInterpreter.Plan plan,
                String targetDatabaseIdentity, String targetUser, JdbcStagingTransfer.Table source,
                JdbcStagingTransfer.Table work, List<WorkTableManagerPort.Column> workColumns,
                List<JdbcStagingTransfer.Column> transferColumns, StagedMappingDefinition.Options options,
                JdbcWorkQualityChecks.Contract quality, int timeoutSeconds,WorkObjectStore.WorkArea workArea) {
            this(owner,plan,targetDatabaseIdentity,targetUser,source,work,workColumns,transferColumns,options,quality,timeoutSeconds,workArea,JdbcStagingTransfer.QueryOptions.defaults());
        }
        public Contract {
            Objects.requireNonNull(owner); Objects.requireNonNull(plan); Objects.requireNonNull(source);
            Objects.requireNonNull(work); Objects.requireNonNull(options); Objects.requireNonNull(quality); Objects.requireNonNull(queryOptions);
            Objects.requireNonNull(workArea);
            workColumns = List.copyOf(workColumns); transferColumns = List.copyOf(transferColumns);
            StagedMappingDefinition.identifier(targetUser);
            if (targetDatabaseIdentity == null || !targetDatabaseIdentity.matches("[0-9a-f]{64}")
                    || !plan.slots().equals(Set.of("WORK_SOURCE_1")) || source.equals(work)
                    || workColumns.isEmpty() || workColumns.size() != transferColumns.size()
                    || timeoutSeconds < 1 || timeoutSeconds > 3600)
                throw new IllegalArgumentException("KM çalışma sözleşmesi geçersiz.");
            List<String> columns = workColumns.stream().map(WorkTableManagerPort.Column::name).toList();
            if (!columns.equals(transferColumns.stream().map(JdbcStagingTransfer.Column::stage).toList())
                    || columns.stream().distinct().count() != columns.size()
                    || !columns.containsAll(quality.requiredColumns())
                    || quality.uniqueKeys().stream().anyMatch(key -> !columns.containsAll(key)))
                throw new IllegalArgumentException("KM kolonları ve kalite sözleşmesi uyuşmuyor.");
            for (var step : plan.steps()) {
                if (step.operation() == AkisKmLanguage.Operation.CHECK_NOT_NULL && quality.requiredColumns().isEmpty()
                        || step.operation() == AkisKmLanguage.Operation.CHECK_UNIQUE && quality.uniqueKeys().isEmpty())
                    throw new IllegalArgumentException("CKM kuralının sabitlenmiş kolon/anahtar bilgisi eksik.");
            }
        }
    }
    public static final class PublishFailure extends RuntimeException {
        private final PublishOutcome outcome;
        PublishFailure(PublishOutcome outcome) { this(outcome, null); }
        PublishFailure(PublishOutcome outcome, Throwable cause) {
            super(outcome == PublishOutcome.ROLLED_BACK ? "KM hedef yayını geri alındı." : "KM hedef yayını mutabakat gerektiriyor.", cause);
            this.outcome = outcome;
        }
        public PublishOutcome outcome() { return outcome; }
    }

    private final Contract contract;
    private final Connection source, workControl, workData;
    private final JdbcTransactionBoundary transaction;
    private final WorkTableManagerPort tables;
    private final WorkObjectStore store;
    private final StagingTransferPort transfer;
    private final JdbcWorkQualityChecks checks;
    private final Guard guard;
    private final TargetPublishPort publisher;
    private WorkTableManagerPort.Created object;
    private JdbcStagingTransfer.Result seal;
    private State state;
    private boolean verified, publicationAttempted;

    public StagedKmRuntime(Contract contract, Connection source, Connection workControl, Connection workData,
            JdbcTransactionBoundary transaction, WorkTableManagerPort tables, WorkObjectStore store,
            StagingTransferPort transfer, JdbcWorkQualityChecks checks, Guard guard, TargetPublishPort publisher) {
        this.contract = Objects.requireNonNull(contract);
        this.source = Objects.requireNonNull(source); this.workControl = Objects.requireNonNull(workControl);
        this.workData = Objects.requireNonNull(workData); this.transaction = Objects.requireNonNull(transaction);
        this.tables = Objects.requireNonNull(tables); this.store = Objects.requireNonNull(store);
        this.transfer = Objects.requireNonNull(transfer); this.checks = Objects.requireNonNull(checks);
        this.guard = Objects.requireNonNull(guard); this.publisher = Objects.requireNonNull(publisher);
        if (source == workControl || source == workData || workControl == workData)
            throw new IllegalArgumentException("Kaynak, çalışma DDL ve çalışma DML oturumları ayrı olmalıdır.");
    }
    @Override public void verify(AkisKmInterpreter.Plan plan) {
        if (verified || !contract.plan().equals(plan)) throw new IllegalStateException("KM planı değişmiş veya runtime tekrar kullanılmış.");
        guard.preflight(); guard.checkpoint(); verified = true;
    }
    @Override public void createWork(String slot) {
        require(slot, null);
        object = tables.create(workControl, contract.owner(), contract.targetDatabaseIdentity(), contract.work(),
                contract.workColumns(), contract.timeoutSeconds(), guard::checkpoint,contract.workArea());
        state = State.READY;
    }
    @Override public void dropWorkIfExists(String slot) {
        require(slot, null);
        tables.dropIfExists(workControl, contract.targetDatabaseIdentity(), contract.work(), contract.timeoutSeconds());
    }
    /** RESUME: take over the sealed work table of the previous attempt instead of creating, loading and sealing a new one. */
    public void adopt(WorkTableManagerPort.Created adopted, JdbcStagingTransfer.Result adoptedSeal) {
        if (verified || state != null || object != null) throw new IllegalStateException("Devralma yalnız ilk adımdan önce yapılabilir.");
        if (!adopted.table().equals(contract.work()) || adoptedSeal == null) throw new IllegalStateException("Devralınan çalışma tablosu sözleşmeyle uyuşmuyor.");
        try {
            guard.verifyWork(adopted);
            try (PreparedStatement statement = workData.prepareStatement("SELECT COUNT(*) FROM " + adopted.table().sql())) {
                statement.setQueryTimeout(contract.timeoutSeconds());
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next() || result.getLong(1) != adoptedSeal.rows() || result.wasNull() || result.next())
                        throw new IllegalStateException("Devralınan çalışma tablosu satır sayısı mühürle uyuşmuyor.");
                }
            }
        } catch (SQLException failure) { throw new IllegalStateException("Devralınan çalışma tablosu doğrulanamadı."); }
        object = adopted; seal = adoptedSeal; state = State.SEALED;
    }
    @Override public long transferJdbc(String slot) {
        require(slot, State.READY);
        guard.verifyWork(object);
        change(State.LOADING, null);
        try {
            seal = transfer.transfer(source, workData, contract.source(), contract.work(), contract.transferColumns(),
                    contract.options(), contract.timeoutSeconds(), guard::checkpoint, transaction, contract.queryOptions());
            return seal.rows();
        } catch (RuntimeException failure) {
            review(); throw failure;
        }
    }
    @Override public void sealWork(String slot, long transferredRows) {
        require(slot, State.LOADING);
        if (seal == null || seal.rows() != transferredRows) throw new IllegalStateException("Aktarım mühür bilgisi uyuşmuyor.");
        try {
            guard.verifyWork(object);
            try (PreparedStatement statement = workData.prepareStatement("SELECT COUNT(*) FROM " + contract.work().sql())) {
                statement.setQueryTimeout(contract.timeoutSeconds());
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next() || result.getLong(1) != seal.rows() || result.wasNull() || result.next())
                        throw new IllegalStateException("Çalışma tablosu satır sayısı mühürle uyuşmuyor.");
                }
            }
            guard.checkpoint(); change(State.SEALED, seal);
        } catch (SQLException failure) {
            review(); throw new IllegalStateException("Çalışma tablosu mühürlenemedi.");
        } catch (RuntimeException failure) {
            review(); throw failure;
        }
    }
    @Override public void checkNotNull(String slot) { check(slot, JdbcWorkQualityChecks.Rule.NOT_NULL); }
    @Override public void checkUnique(String slot) { check(slot, JdbcWorkQualityChecks.Rule.UNIQUE); }
    private void check(String slot, JdbcWorkQualityChecks.Rule rule) {
        require(slot, State.SEALED);
        try {
            checks.verify(workData, contract.work(), contract.quality(), rule, contract.timeoutSeconds(), () -> {
                guard.checkpoint(); guard.verifyWork(object);
            });
        } catch (RuntimeException failure) {
            review(); throw failure;
        }
    }
    @Override public long atomicReplace(String slot) {
        require(slot, State.SEALED);
        if (publicationAttempted) throw new IllegalStateException("KM yayını otomatik tekrar edilemez.");
        guard.verifyWork(object);
        tables.grantRead(workControl, object, contract.targetUser(), contract.timeoutSeconds(), guard::checkpoint);
        guard.checkpoint(); publicationAttempted = true;
        PublishResult result;
        try { result = Objects.requireNonNull(publisher.publish(object, seal)); }
        catch (RuntimeException failure) { throw new PublishFailure(PublishOutcome.UNKNOWN, failure); }
        if (result.outcome() != PublishOutcome.COMMITTED && result.outcome() != PublishOutcome.ALREADY_RECORDED)
            throw new PublishFailure(result.outcome());
        if (result.insertedRows() != seal.rows()) throw new PublishFailure(PublishOutcome.UNKNOWN);
        try { change(State.CONSUMED, null); }
        catch (RuntimeException failure) { throw new PublishFailure(PublishOutcome.UNKNOWN); }
        return result.insertedRows();
    }
    @Override public void dropWork(String slot) {
        require(slot, State.CONSUMED);
        // Seal/quality SELECTs can leave an ACCESS SHARE lock on PostgreSQL until the work-data transaction ends.
        // End that read transaction before the control connection attempts DROP TABLE.
        try { transaction.commit(); }
        catch (SQLException failure) { throw new IllegalStateException("Çalışma tablosu okuma transaction'ı kapatılamadı."); }
        tables.cleanup(workControl, contract.owner(), object.uuid(), contract.timeoutSeconds());
        state = State.DROPPED;
    }
    private void require(String slot, State expected) {
        if (!verified || !"WORK_SOURCE_1".equals(slot) || state != expected)
            throw new IllegalStateException("KM çalışma adımı sırası veya slotu geçersiz.");
        guard.checkpoint();
    }
    private void change(State next, JdbcStagingTransfer.Result sealed) {
        store.transition(contract.owner(), object.uuid(), state, next, null, sealed);
        state = next;
    }
    private void review() {
        // Lease loss may prevent this write. The previous state remains non-droppable and cannot publish.
        try { change(State.REVIEW_REQUIRED, null); } catch (RuntimeException ignored) { }
        state = State.REVIEW_REQUIRED;
    }
}
