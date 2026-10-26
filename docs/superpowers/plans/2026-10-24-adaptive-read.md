# Adaptive read implementation plan

> Execute inline with executing-plans and TDD; one independent final review. User explicitly requested immediate implementation; no repeated design approval.

**Goal:** Improve and explain the Oracle read path, then measure1/2/5GiB on the same1M records with all previous results preserved.
**Architecture:** Extend existing bounded JDBC/COPY transfer and confirmed-progress JSON. Probe before tuning, preserve legacy contracts and quarter-heap/shared admission, expose requested/effective resource limits.
**Tech Stack:** Java25/JDBC/Oracle/PostgreSQL COPY; Python collector; existing view-onlyHTML.
**Spec:** ../specs/2026-10-24-adaptive-read-design.md

## Global constraints

- SourceTTBP.SKY_DIT_PERF_50 and targetSKY_DIT_PERF.SKY_DIT_PERF_50 stay1M/50columns; UAT only, PRODASKIDA.
- Default1024MiB unchanged; request1/2/5GiB, record heap clamp explicitly; no heap increase to consume desktop memory.
- Preserve all16 pre-change run dictionaries, volumeSummaries and comparisonSummary. No commit/reset; current checkout contains required prior changes.
- No build/restart/heavy checks during measured runs. Same new binary/settings across all nine trials.

## Review focus

- A huge/wide row must shrink windows and fail early within allowance; zero/unknown width remains conservative.
- Old publication/progress JSON and manual transfer behavior remain compatible.
- GC pressure, shared admission, cancellation and uncertain commit do not leak permits or publish partial targets.
- Throughput noise must not trigger unbounded window growth; requested5GiB must never be labeled effective5GiB after clamping.
- Report grouping must preserve prior cohorts and never pool budgets, volumes or failed/unvalidated runs.

## Tasks

- [x] 1. Snapshot baseline; bounded read-only probe of real JDBC source query/fetch/LOB path and administrative/heap limits. Persist evidence and tuning decision.
- [x] 2. Diagnostics RED→GREEN: extend TransferProgress/TransferTelemetry, TransferBuffer and JDBC/COPY phase recording; tests for legacy JSON, counters, known source/LOB delays and commit failure. Scoped Maven tests green.
- [x] 3. Adaptive windows RED→GREEN: real budget/row/throughput feedback, stable plateau, GC/large-row shrink, safety ceilings. Keep manual/legacy behavior. Full backend/integration suite green; actual new1M measurements provide end-to-end before/after evidence.
- [x] 4. Report/collector tests RED→GREEN: separate new budget cohorts, effective-limit/read-subphase display, old16 immutable. Independent review before measurements; material findings fixed with regression tests.
- [ ] 5. Publish same interface UAT1/2/5GiB profiles; verify pinned plans, inherited environment policy and worker pool. Same binary. Run rotation1→2→5 three times with CPU/RAM/full SHA and prefix evidence, report updated after each run. Persist checkpoint throughout.
- [ ] 6. Final live counts, zero active runs, PRODASKIDA, environment default restored1024MiB; HTML/render/collector audits and preserved old data. Report measured gains/plateau honestly and hand off same report.

Each implementation task writes tests first, runs them RED, implements minimal change, runs GREEN and relevant suite; record commands/results/rulings in .superpowers/sdd/2026-10-24-adaptive-read/progress.md. Keep exact telemetry/window names in the ledger when introduced so later tasks consume the actual interfaces. No source commits required for this dirty live workspace.
