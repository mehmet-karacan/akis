# Akış implementation status

Status date: 2026-09-12

## Clean `akis` rebase progress

- Identity, RBAC, project membership and project repositories run against the
  clean `akis` schema and pass temporary-database acceptance tests.
- Connection, typed connection policy, internal credential binding,
  physical/logical schemas, environments and schema bindings now have clean
  repository coverage. Connection test evidence and revision activation no
  longer depend on the legacy lifecycle tables.
- Project folders, project definitions, allowed system-library definitions,
  drafts, immutable versions and version dependencies now have a clean Turkish
  schema contract. Repository acceptance tests cover folder movement, typed
  definition mapping, optimistic draft updates, immutable version creation and
  project/system scope separation.
- Models, submodels, data objects, immutable schema/column/constraint snapshots,
  Oracle connection-test provenance and definition-version data bindings now
  use the clean `akis` schema. Temporary PostgreSQL acceptance tests exercise
  catalog vocabulary translation, hierarchy creation, deterministic snapshot
  deduplication and ordered relational metadata.
- Validation evidence and generated Scenario plans now run on the clean schema.
  The database enforces source-version scope and successful validation, while
  repository tests prove deterministic compilation and idempotent generation.
- Environment risk/policy, runnable Scenario releases, pinned physical bindings
  and append-only approvals now run on the clean schema. “Publication” remains
  an execution safety boundary, not a top-level design workspace.
- Manual requests, idempotency reservations, runs, hierarchical run steps and
  append-only events now have a clean control-plane model and repository test.
  The step tree supports package/load-plan nesting without duplicating connection
  timeout policy into Procedure steps.
- Project bundle definition queries and the append-only audit log now run on the
  clean schema. Their temporary-database acceptance gate covers project/folder/
  definition round-tripping and immutable audit evidence.
- Worker profiles, single-flight run claims, bounded heartbeats and target
  fencing now use the clean schema and pass a generated-database acceptance test.
- Pilot preflight, immutable publish intent, durable checkpoint and successful
  terminal transition now execute against the clean schema with exact evidence.
- The connection UI no longer loads or manages separate secret-reference
  objects. It records only the name of a server-side environment variable; its
  value stays outside PostgreSQL, API responses, bundles and Git.
- Reconciliation and Procedure task journals still use the legacy schema until
  their clean operations gate and acceptance tests are complete. The persistent
  local database has not been destructively cut over.

## Current completion estimate

- Controlled Oracle MVP first vertical slice: **complete**.
- Broad ODI-like enterprise platform: **approximately 38% complete / 62% remaining**.

The MVP estimate means one governed Oracle-to-Oracle procedure can be designed,
versioned, bound, approved, executed manually, observed and reconciled. The broader
estimate also includes packages, variables, sequences, load plans, scheduling,
multi-engine support, deployment automation and production operations.

## Completed foundation

- PostgreSQL metadata repository and Flyway migrations through V020.
- English-first UI with Turkish support and professional project explorer.
- Immutable JSON definitions and versions for mappings, procedures, packages,
  variables, sequences, user functions, knowledge modules and load plans.
- Oracle JDBC/JNDI connection definitions, append-only test evidence and
  `DRAFT -> TESTED -> ACTIVE` lifecycle.
- SKY and GPU topology, models, logical/physical schemas and environment bindings.
- Server-produced Oracle 19c schema discovery, deterministic fingerprints and
  immutable provenance evidence.
- Ordered Procedure V2 designer with SQL/PLSQL tasks, risk classes, explicit
  approval, rowset output and batch input contracts.
- Catalog-aware definition binding candidates; users do not need to paste UUIDs.
- Scenario V2 compilation, immutable publication manifests, release hashes and
  approval-required publication state.
- PostgreSQL run leases, fencing, append-only step journal and reconciliation
  primitives with fail-closed tests.
- Published Procedure source preflight: exact immutable plan/binding resolution,
  trusted snapshot re-attestation, a fresh read-only Oracle session, bounded typed
  rowset encoding and payload hash; no source rows are returned by the API.
- Published Procedure target preflight: live Oracle 19c database/container and
  table identity fencing, trusted target snapshot re-attestation, ownership and
  TRUNCATE/INSERT/DBMS_STATS privilege checks in a read-only session.
- Controlled Procedure executor for ordered TRUNCATE, bounded source SELECT,
  single-use in-memory rowset, batch INSERT and allow-listed PL/SQL; every target
  step re-attests schema, database identity, target fence and privileges on the
  same session before crossing the mutation boundary.
- Single-flight Procedure worker with run/target leases, heartbeat supervision,
  exact step journal acknowledgements and fail-closed outcome classification.
  Manual requests, Procedure runtime and worker remain separately disabled by
  default and in CI.
- Publication operations UI now exposes independent SKY source and GPU target
  read-only readiness checks beside approval, with bilingual result summaries.
- Project bundle V1 export/import for definition metadata. Secret values remain
  outside bundles and Git.

## Current HAKEDIS_TIPI pilot state

- Procedure: `LOAD_HAKEDIS_TIPI`
- Immutable version: v2
- Tasks: `TRUNCATE_TARGET`, `READ_SOURCE`, `INSERT_TARGET`,
  `GATHER_TARGET_STATS`
- Source: trusted `SKY / TTBP.HAKEDIS_TIPI` snapshot
- Target: trusted `GPU / INNOVA_ODI.STG_HAKEDIS_TIPI` snapshot
- Scenario: compiled as plan version 2
- Publication: created with `ORACLE_PROCEDURE_V1` capability and currently
  `ONAY_BEKLIYOR`, because `TRUNCATE` is irreversible.
- Read-only source preflight: passed against live SKY with 33 rows, 13 supported
  columns and `targetSessionOpened=false`.
- Read-only target preflight: passed against live GPU (`CDB19C / CT_GPU_TESTDB`)
  for `INNOVA_ODI.STG_HAKEDIS_TIPI`; ownership, TRUNCATE, INSERT and DBMS_STATS
  evidence passed with `sourceSessionOpened=false`.
- Publication approved and activated after both read-only preflights.
- Accepted pilot run: `7d213ae5-b6d3-44a7-b348-b708ebb37776`.
- Durable task results: TRUNCATE succeeded, SKY read 33 rows, GPU batch INSERT
  wrote 33 rows, DBMS_STATS succeeded, and the run ended `BASARILI`.
- Post-run canonical comparison: SKY 33 rows, GPU 33 rows, with identical
  payload hash `15a3fb9db987b0b5ed87c6404c1ecd33d9c33ff022989426bc53de9df546d53d`.
- An earlier guarded attempt exposed a control-plane ordering defect before any
  Procedure step began and was closed by the safe lease reaper. A second attempt
  truncated the staging table but rejected an inconsistent rowset byte receipt
  before INSERT; the canonical receipt was corrected and the accepted run restored
  and verified the target data.

## Controlled Oracle MVP acceptance

The first governed Oracle-to-Oracle Procedure has been designed, versioned,
bound, approved, executed, observed and verified through Akış. Runtime and worker
flags remain local-only deployment controls; CI/CD remains disabled.

## Work after the first pilot

- Bundle V2 for complete project export/import including topology references,
  procedures, mappings, packages, variables and sequences; never secret values.
- Runtime semantics for variables, sequences, packages and load plans.
- Scheduling, retry/restart policies, notifications and operator runbooks.
- OIDC/SSO, production RBAC review, retention, backup/restore and observability.
- CI/CD remains intentionally disabled until the bootstrap and pilot gates are
  accepted explicitly.
- Additional databases, reusable knowledge modules, lineage, CDC and scale tests.
