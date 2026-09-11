# Akış implementation status

Status date: 2026-09-11

## Current completion estimate

- Controlled Oracle MVP: **approximately 88% complete / 12% remaining**.
- Broad ODI-like enterprise platform: **approximately 37% complete / 63% remaining**.

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
- No business-table DDL or DML has been executed by Akış yet.

## Remaining work for the controlled Oracle MVP

1. Execute the controlled HAKEDIS_TIPI pilot: explicitly approve the pending
   publication, enable manual requests/runtime/worker locally, submit one run,
   and observe its durable step timeline. This is the first authorized DDL/DML.
2. Verify source/target row counts and deterministic business-data evidence,
   then document operator acceptance or invoke the manual intervention path.
3. Add execution-wide user cancellation after claim and richer reconciliation
   actions; queued cancellation and fail-closed lease recovery already exist.

## Work after the first pilot

- Bundle V2 for complete project export/import including topology references,
  procedures, mappings, packages, variables and sequences; never secret values.
- Runtime semantics for variables, sequences, packages and load plans.
- Scheduling, retry/restart policies, notifications and operator runbooks.
- OIDC/SSO, production RBAC review, retention, backup/restore and observability.
- CI/CD remains intentionally disabled until the bootstrap and pilot gates are
  accepted explicitly.
- Additional databases, reusable knowledge modules, lineage, CDC and scale tests.
