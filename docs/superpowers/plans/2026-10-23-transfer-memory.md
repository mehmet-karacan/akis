# Transfer Memory Implementation Plan

> For agentic workers: implement task-by-task using test-driven development in the current authorized workspace.

**Goal:** Environment-inherited 1 GiB transfer budget and faster bounded reads, verified by three comparable new 500K runs.
**Architecture:** Reuse environment policy JSON, pin resolved memory in publication options, enforce it through the shared buffer policy. Preserve old options and evidence.
**Tech Stack:** Java, Spring JDBC, PostgreSQL migrations, React/TypeScript, Python measurement collector.
**Spec:** ../specs/2026-10-23-transfer-memory-design.md

## Constraints and review focus

- Default 1024 MiB; reject fractional/out-of-range settings; preserve unrelated environment policy.
- Interface inherits; old publications still parse; reduced environment ceiling cannot be bypassed by a pinned plan.
- Estimated buffer memory respects heap/concurrency and unusually wide rows/LOBs; no claim of hard RSS enforcement.
- Preserve cancellation, type fidelity and legacy explicit-mode behavior.
- Keep historical run UUIDs/data unchanged; separate baseline and new comparison cohorts.

## Task 1: Environment budget and inheritance

Files: topology service/controller/repository; knowledge budget policy and mapping options; WorkAreaPolicyService; StagedMappingPlanner/Runtime resolver; V071 migration; environment forms/API and tests.

- [x] Add failing tests for defaults, invalid budgets, policy preservation and physical-plan inheritance.
- [x] Run targeted tests, implement policy persistence and pin resolved byte budget; keep old constructors/API callers compatible.
- [x] Add environment editor setting and inherited-budget preview; verify frontend behavior and build.

## Task 2: Bounded read optimization

Files: TransferBuffer, TransferResourcePolicy, tests.

- [x] Reproduce conservative read window with the fifty-column profile; test larger windows under 1 GiB, wide/unknown rows and legacy behavior.
- [x] Implement separate bounded read/write allocations with conservative row/LOB estimates and heap/concurrency headroom.
- [x] Run unit suite, real PostgreSQL transfer/type/lock integration tests, and frontend checks sequentially.

## Task 3: Three new 500K measurements and report

Files: local performance config/finalizer and existing HTML template as needed for cohort comparisons.

- [x] Publish verified UAT 500K definition with inherited 1024 MiB budget; capture publication and runtime settings.
- [x] Run three full 500K transfers with fresh authentication, CPU/RAM sampling and full SHA validation.
- [x] Add separate cohort comparison without changing historical runs; verify final counts and rendered report.

2026-10-24 COMPLETE: important review fixes verified RED→GREEN (bounded LOB materialization and atomic environment-policy updates). Backend897 tests/1skip (RunExportScaleTest), PostgreSQL integration8/8, UI436/436+build, collector29/29. New500K three repetitions all SHA-verified:1007.072703/917.975075/727.464718s. Historical10 runs unchanged; report13 total/12 passed including preserved old failure. Median1561.708101→917.975075s (41.22% reduction); CPU/RAM increase explicitly reported. FINAL_REPORT_OK/COHORT_RENDER_OK and live DB audit passed. Source/target500000, active0, PROD scheduleASKIDA. No further volume started.
