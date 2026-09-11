# Akış implementation status

Status date: 2026-09-11

## Current completion estimate

- Controlled Oracle MVP: **approximately 65% complete / 35% remaining**.
- Broad ODI-like enterprise platform: **approximately 30% complete / 70% remaining**.

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
- No business-table DDL or DML has been executed by Akış yet.

## Remaining work for the controlled Oracle MVP

1. Implement the production Oracle Procedure executor session:
   bounded source SELECT, typed rowset storage, target batch INSERT, approved
   TRUNCATE and allow-listed `DBMS_STATS` execution.
2. Enforce execution-wide deadlines, cancellation, connection cleanup and
   deterministic error/outcome classification for Oracle implicit commits.
3. Connect Procedure execution to the guarded worker orchestrator while keeping
   feature flags fail-closed until crash/retry tests pass.
4. Add publication approval and dry-run/preflight views to the UI.
5. Add operational run/step log details, row counts, durations, error guidance
   and reconciliation actions to the landing dashboard.
6. Run the HAKEDIS_TIPI pilot in stages: read-only preflight, target privilege
   check, bounded rehearsal, explicit approval, real transfer and source/target
   count/hash verification.

## Work after the first pilot

- Bundle V2 for complete project export/import including topology references,
  procedures, mappings, packages, variables and sequences; never secret values.
- Runtime semantics for variables, sequences, packages and load plans.
- Scheduling, retry/restart policies, notifications and operator runbooks.
- OIDC/SSO, production RBAC review, retention, backup/restore and observability.
- CI/CD remains intentionally disabled until the bootstrap and pilot gates are
  accepted explicitly.
- Additional databases, reusable knowledge modules, lineage, CDC and scale tests.

