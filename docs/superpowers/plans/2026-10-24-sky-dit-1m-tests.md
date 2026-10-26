# SKY DIT 1M Performance Execution Plan

**Goal:** Increase the existing Oracle source to1,000,000 rows, run the real DIT UAT interface three times with CPU/RAM measurement/full content validation, append to the same report preserving all previous results.
**Architecture:** Reuse the existing tested seeder, AUTO1GiB publication and measurement/validation infrastructure. No backend/driver/buffer changes. New1M runs stay in the `memory-budget-1g` cohort, so they do not merge with older baseline medians.
**Spec:** User request2026-10-24; retain existing50-column SKY_DIT_PERF_50 names and resource-profile design at ../specs/2026-10-23-transfer-memory-design.md.

## Constraints and verification

- Preserve every field of all13 existing runs against `pre-1m-baseline.json`.
- Add only source IDs500001..1000000 using existing deterministic batch seed SQL; never reseed/delete original source rows.
- Target resets only reserved `SKY_DIT_PERF.SKY_DIT_PERF_50` between independent repetitions; transfer is DIT, not collector.
- UAT only, environment memory budget1073741824bytes, worker/profile unchanged. Keep PROD scheduleASKIDA.
- Three successful app runs are insufficient alone: source/target full50-column SHA-256, exact total1M, no missing/duplicate records must pass per repetition.
- CPU/RAM captures PID tree and total host separately; validation time excluded from application transfer duration.
- No compile/restart/heavy checks during measured transfer. No1M before Oracle source and pinned SELECT range are verified.

## Tasks

- [x] Snapshot/audit previous13 runs, confirm DB state and no active runs.
- [x] Seed source to1M and verify dense IDs1..1000000 plus50-column contract.
- [x] Save a new version of the existing interface with only the upper ID filter changed to1000000; compile and preview/publish UAT. Assert inherited1GiB budget and exact source parameter range; old publications unchanged.
- [x] Run three sequential1M repetitions using established suite with fresh login per run, CPU/RAM sampling and full-content SHA validation. Persist run UUIDs and progress checkpoint.
- [x] Finalize only new1M run metrics/findings, preserve prior run dictionaries and500K comparisonSummary; append a separate1M summary. Final16 runs/15 verified/1 historical failed run.
- [x] Verify actual HTML tables/charts/controls, source+target1M, active0, old13 unchanged; hand off same report. No larger volume, no commit/reset/PROD run.

Completed2026-10-24:1M durations1899.176491s,1688.627252s,1407.25877s; median1688.627252s. `FINAL_1M_REPORT_OK`, `COHORT_RENDER_OK`, collector29/29 and `FINAL_1M_DATA_AUDIT_OK`. Every run has full1M/50column SHA equality plus original500K source/target prefix equality. Previous13 run dictionaries and500K comparisonSummary unchanged. Source/target each1M IDs1..1M, active0, PRODASKIDA. Final evidence/checkpoint: ../../testing/performance/CHECKPOINT-2026-10-24-1M.md.

Operational-only extension of the approved workflow; no application feature implementation or new tuning. Existing collector29 tests and report cohort renderer are the relevant preflight checks. Only local performance config/checkpoint/finalizer files will be added.
