# Automatic transfer resource management

New mapping definitions use `options: {resourceMode: "AUTO", allowEmptySource: false}`.
The interface does not ask operators to size bytes, batches or fetch windows.
Existing mappings switch only when the operator selects automatic management and publishes a new version.
Previously published explicit options remain readable and retain their pinned limits.

## Execution

- Publication resolves AUTO from the server's `akis.execution.transfer` policy and pins the effective options and authorized work-area snapshot in the physical manifest.
- Runtime revalidates the work-area permission, policy version and current server ceilings before staging and before target publication. AUTO does not grant database or schema permissions.
- Staged jobs share a fair, process-local admission semaphore, default one transfer at a time. The slot is held through final publication and cleanup; queued admission is cancellable.
- Source fetch, staging batches and PostgreSQL COPY operate synchronously. A slow writer naturally stops further reads; no unbounded producer queue is allocated.
- AUTO batches use a conservative resident-payload estimate with a budget of at most 4 MiB, reduced on small JVM heaps. The row-count batch setting is an upper bound; wide rows flush earlier. This is a payload budget, not a promise that JVM RSS stays at 4 MiB.
- Every AUTO source cursor starts with one row until projected widths are known. Declared string widths and bounded LOB prefetch determine the subsequent window; unknown widths remain conservative even if the first row is small. Oracle LOBs use locator streams, 4 KiB LOB prefetch and bounded chunk reads. This avoids the JDBC LOB data interface that can reduce Oracle row prefetch to one. Prefetch shrinks further for unusually wide rows.
- Individual rows remain subject to the existing 16 MiB logical-row safety limit. Text LOB reads stop at 8 Mi characters, binary LOB reads at 16 MiB; canonical framing and the aggregate row limit can reject them earlier. Oversized cells fail safely before target publication; this version does not spool arbitrarily large individual LOBs to disk.
- PostgreSQL staging uses COPY in bounded batches. Final publication continues to use the existing fenced database-side `INSERT … SELECT`/MERGE path and ledger. Per-batch staging commits never authorize publishing an incomplete stage.
- COPY batches are encoded once as UTF-8 bytes, not sent through the driver's character Reader API. This preserves supplementary Unicode characters even at driver chunk boundaries.
- Transfer progress counts confirmed staging commits only. Rows, logical bytes, elapsed time, read/conversion, write and commit time are sampled at most every five seconds and on successful completion. Staging progress is not a count of final-target inserts.
- Cancellation remains owner/run/generation scoped and interrupts registered source/control/data sessions when the run enters the cancellation state, even when that state is no longer heartbeat-renewable.

## Operator policy

Server deployment settings, rather than mapping forms, own the safety ceilings:

| Environment setting | Default | Meaning |
| --- | --- | --- |
| `AKIS_TRANSFER_MAX_CONCURRENT` | 1 | Simultaneous staged transfers per backend process |
| `AKIS_TRANSFER_BATCH_ROWS` | 1000 | Maximum rows in an AUTO batch; memory budgeting may flush sooner |
| `AKIS_TRANSFER_FETCH_ROWS` | 256 | Upper bound on source fetch rows; LOB/window budgeting may lower it |
| `AKIS_TRANSFER_MAX_ROWS` | 100000000 | Whole-transfer safety ceiling, independent of the source filter |
| `AKIS_TRANSFER_MAX_BYTES` | 1099511627776 | Whole-transfer logical-byte ceiling, independent of the in-memory buffer |

The byte ceiling is not reserved memory or a disk-space guarantee. Database storage capacity and server resource limits remain deployment concerns. A multi-node installation must also size its worker concurrency across nodes.

Saving work-area permissions from the UI preserves enablement, same-schema permission, object count and retention, while the server supplies the row/byte ceilings. Changed policy versions require republication.

Migration `V069__aktarim_ilerleme_metrikleri.sql` adds nullable per-step progress JSON; historical steps continue to have no progress samples.
Migration `V070__calistirma_iptal_tamamlama.sql` adds owner/generation-scoped terminal cancellation acknowledgement after runtime sessions unwind. Uncertain publication or procedure-write evidence remains `SONUC_BELIRSIZ`, never a clean cancellation.
Package children acknowledge independently before returning to their parent. Transient acknowledgement failures receive three bounded attempts; expired persisted cancellation requests are recovered in small `SKIP LOCKED` batches before worker claims, including after restart. Recovery does not renew execution authority and does not acknowledge a still-live lease.

## Verification scope

Regression coverage includes AUTO parsing, legacy plan verification, signed server-resolved options, policy permission/ceiling checks, wide-row JDBC/COPY batching, Unicode/NULL/empty LOB handling, stream bounds, uncertain commits, concurrency admission and cancellation.
Disposable PostgreSQL acceptance tests compare all 50 mixed-type columns across 1,000 records using bidirectional `EXCEPT ALL`, apply the complete baseline through V070, and exercise cancellation acknowledgement. They do not connect to Oracle UAT or the application's metadata database.
Live UAT throughput and full 250,000-row SHA validation must be measured with a new AUTO publication; unit tests do not establish a production throughput or CPU percentage guarantee.

For a controlled comparison, keep the same Oracle source and PostgreSQL target, select automatic management on the mapping, simulate and publish a new version, and repeat the 250,000-row run three times while recording whole-machine and application-process CPU/RAM separately. Include the read/conversion, write and commit timings in the HTML results. Do not compare a new AUTO definition against a still-pinned legacy publication or silently mix failed attempts with successful runs.

Verification commands (Java 25; disposable Docker database required for the last command):

```powershell
.\mvnw.cmd -pl backend test
# In frontend:
npm.cmd test -- --run
npm.cmd run build
# In repository root:
$env:AKIS_CANCELLATION_DOCKER_IT = 'true'
.\mvnw.cmd -pl backend '-Dit.test=AutomaticTransferContainerIT,CancellationCompletionIT' test-compile failsafe:integration-test failsafe:verify
```

Oracle reference: <https://docs.oracle.com/en/database/oracle/oracle-database/19/jjdbc/performance-extensions.html>.
