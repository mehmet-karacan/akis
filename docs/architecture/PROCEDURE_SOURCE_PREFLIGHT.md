# Procedure source preflight

The Procedure source preflight proves the read half of an immutable published
Oracle Procedure without creating a run and without opening the target database.
It is intended for operator validation before a destructive publication is
approved.

## Endpoint

`POST /api/v1/projects/{projectUuid}/procedure-preflights`

Request body:

```json
{
  "publicationUuid": "00000000-0000-0000-0000-000000000000"
}
```

The caller needs both `PUBLICATION_READ` and `RUN_START`. Only publications with
`ORACLE_PROCEDURE_V1` capability and status `AKTIF` or `ONAY_BEKLIYOR` are
eligible. A successful response contains identifiers, hashes, column types, row
and byte counts, timing, `sourceReadOnly=true` and
`targetSessionOpened=false`. It never contains source row values or credentials.

## Fail-closed sequence

1. Load the exact publication, Scenario plan and physical manifest.
2. Recompile and verify release, Scenario and runtime-plan hashes.
3. Require exactly one bounded `SOURCE / SQL / READ_ONLY` task whose command is
   an explicit-column `SELECT` from the bound `OWNER.OBJECT` and has no binds.
4. Load the exact trusted Oracle snapshot and provenance tied to the publication.
5. Open a fresh purpose-specific Oracle session with read-only and auto-commit
   enabled; target connection metadata is not loaded.
6. Re-attest Oracle 19c live columns against the pinned snapshot on the same
   session.
7. Execute the published SELECT with an additional sentinel row, strict maximum
   rows, query/network timeouts and typed `NUMBER`, `VARCHAR2`, `TIMESTAMP(0..6)`
   decoding.
8. Enforce per-cell and whole-payload byte limits, compute a deterministic payload
   hash, discard row values and return only the summary.

Source views are deliberately rejected in this first slice until a separate view
attestation contract is implemented. The endpoint does not bypass publication
approval and cannot execute TRUNCATE, INSERT, DDL or PL/SQL.
