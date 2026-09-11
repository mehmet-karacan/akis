# ODI 12c Repository Metadata Inventory

Status: **Catalogued from the 2026-09-12 DBeaver export**

This document is the durable, Git-safe summary of the supplied ODI repository
metadata. The raw archive, extracted workbooks and normalized full inventory are
kept locally under:

```text
.data/oracle-metadata/odi12c-repository-2026-09-12/
```

The `.data` directory is intentionally ignored by Git because the source files
contain database, schema, tablespace and repository object identifiers. Future
design work should use the normalized `inventory.json` instead of reopening and
rescanning the archive.

## 1. Version clarification

The archive is named `odi12c.7z`, but its product-version result identifies the
database engine as **Oracle Database 19c Enterprise Edition 19.0.0.0.0**.

The safe interpretation is:

- product/repository family supplied by the user: ODI 12c,
- database engine hosting the repository: Oracle Database 19c,
- exact ODI repository patch level: not independently verified by this export.

Akış must therefore keep “ODI product version” and “Oracle database version” as
different metadata concepts.

## 2. Supplied coverage

| Dataset | Rows | Use |
| --- | ---: | --- |
| Database identity | 1 | Local source identification |
| Product version | 1 | Database-engine capability |
| Object type counts | 3 | Completeness check |
| Object inventory | 1,480 | Object/status lookup |
| Tables | 261 | Datastore inventory |
| Columns | 3,773 | Type and nullability model |
| Table comments | 261 | All comments are empty |
| Constraint join rows | 2,325 | PK, UK, FK and CHECK/NOT NULL metadata |
| Indexes | 1,082 | Index inventory |
| Index columns | 1,393 | Ordered index-column membership |
| LOB columns | 137 | Large-value storage inventory |

The user reported that unnamed/missing result files had no rows and therefore
did not export them. Column comments, partition keys, views/materialized views,
sequences, procedures/arguments, triggers, synonyms, dependencies and invalid
objects are consequently recorded as **not supplied, reported empty**, not as a
general Oracle/ODI capability conclusion.

The inventory query intentionally excluded source code, view SQL, trigger body,
column defaults, CHECK expressions, database-link names, grants and optimizer
statistics.

## 3. Structural totals

| Measure | Count |
| --- | ---: |
| Tables | 261 |
| Columns | 3,773 |
| Average columns per table | 14.46 |
| Distinct constraints | 2,029 |
| Primary keys | 241 |
| Unique constraints | 342 |
| Foreign keys | 383 |
| CHECK/NOT NULL constraints | 1,063 |
| Indexes | 1,082 |
| Unique indexes | 583 |
| Non-unique indexes | 499 |
| LOB columns | 137 |

All 1,480 exported objects are valid. All 2,029 constraints are enabled. The
snapshot contains no partitioned, temporary or compressed tables and no identity
columns. All 1,082 indexes are normal indexes.

## 4. Data-type profile

| Oracle type | Columns | Share |
| --- | ---: | ---: |
| VARCHAR2 | 2,143 | 56.8% |
| NUMBER | 1,232 | 32.7% |
| DATE | 259 | 6.9% |
| CLOB | 137 | 3.6% |
| LONG RAW | 2 | 0.1% |

There are 1,063 non-nullable and 2,710 nullable columns. The Akış Oracle catalog
model must preserve precision, scale, byte/character length semantics,
nullability and LOB identity without converting missing precision/scale to zero.

## 5. Repository families

The 261 tables divide into two prefixes:

- `SNP_*`: 239 ODI repository tables,
- `OGG_*`: 22 GoldenGate-related tables.

The following is a functional index, not a claim that Akış should reproduce the
ODI physical schema.

| Product concept | Representative repository tables | Akış design use |
| --- | --- | --- |
| Projects and folders | `SNP_PROJECT`, `SNP_FOLDER` | Mandatory project context and nested object tree |
| Technologies and connections | `SNP_TECHNO`, `SNP_CONNECT`, `SNP_CONNECT_PROP` | Provider-aware connection cards and revisions |
| Physical/logical schemas | `SNP_PSCHEMA`, `SNP_LSCHEMA`, `SNP_CONTEXT`, `SNP_PSCHEMA_CONT` | Connection → physical schema and environment mapping |
| Agents | `SNP_AGENT`, `SNP_LAGENT`, `SNP_PLAN_AGENT` | Future runtime/agent placement, not primary MVP navigation |
| Models and datastores | `SNP_MODEL`, `SNP_SUB_MODEL`, `SNP_TABLE`, `SNP_COL` | Discovered model, table and ordered column catalog |
| Keys and relationships | `SNP_KEY`, `SNP_KEY_COL`, `SNP_JOIN`, `SNP_JOIN_COL`, `SNP_COND` | Mapping validation and relationship discovery |
| Mappings/interfaces | `SNP_MAPPING`, `SNP_MAP_COMP`, `SNP_MAP_CONN`, `SNP_MAP_EXPR`, `SNP_POP` | Structured interface/mapping workspace |
| Procedures and packages | `SNP_TRT`, `SNP_PACKAGE`, `SNP_STEP` | Ordered multi-step procedure/package authoring |
| Variables and sequences | `SNP_VAR`, `SNP_SEQUENCE`, `SNP_SEQ_DATA` | First-class project objects |
| Load plans | `SNP_LOAD_PLAN`, `SNP_LP_STEP`, `SNP_LP_VAR` | Future orchestration hierarchy |
| Scenarios and immutable snapshots | `SNP_SCEN`, `SNP_SCEN_STEP`, `SNP_SCEN_TASK`, `SNP_VERSION` | Runnable-version UX backed by immutable release data |
| Runs and task logs | `SNP_SESSION`, `SNP_SESS_STEP`, `SNP_SESS_TASK`, `SNP_SESS_TASK_LOG` | Execution tree and step detail projection |
| Scheduling | `SNP_SCHEDULE_EXEC`, `SNP_PLAN_AGENT` | Future operations workspace |

## 6. Confirmed product-design implications

### Connections

The repository independently confirms distinct concepts for technology,
connection, physical schema, logical schema and context/environment mapping.
Akış should not flatten these into one generic form or duplicate their values in
procedure JSON.

The user-facing hierarchy remains:

```text
Provider
└─ Connection
   ├─ Connection revision and test state
   ├─ Physical schemas
   └─ Logical-schema/environment mappings
```

Raw password/reference fields visible in the legacy repository are evidence for
what Akış must avoid. Secret values stay outside the UI, catalog export and
definition JSON.

### Development explorer

Separate project, folder, mapping, package, procedure, variable and sequence
entities support the planned project-scoped tree. Folder membership must not be
used as object identity; stable Akış UUIDs and deep links remain authoritative.

### Procedure editor

The legacy step model contains order, type, context, next-step/error behavior and
retry fields. Akış should reuse the useful concepts—ordered steps, source/target
roles, explicit failure policy—without copying ODI's dense table-shaped editor.

Connection and schema context must be resolved from bindings. Timeout belongs to
the connection policy. Source SELECT and target commands stay separate. The
current controlled Oracle runtime contract remains the authority over what the UI
may offer.

### Runnable versions

Scenario, scenario-step and scenario-task tables support retaining immutable
execution snapshots. The primary UI can hide “Publication” terminology, but the
backend release hash, approval and physical manifest must remain.

### Execution explorer

Session → session step → session task → task log is strong evidence for the
planned hierarchy:

```text
Definition / Package / Interface
└─ Run
   └─ Ordered Step
      └─ Task/Event Detail
```

The Akış API should expose a typed step projection containing status, ordinal,
timing, row counts, sanitized error data and resolved connection/schema labels.
The UI must not reconstruct this hierarchy from arbitrary event JSON.

## 7. Important limitations

- This snapshot contains schema metadata, not repository table rows.
- It does not reveal actual ODI projects, mappings, packages or run history.
- Empty table comments mean business semantics cannot be derived from comments.
- CHECK expressions and column defaults were intentionally omitted.
- No procedure/view/trigger source text was collected.
- Oracle 12.1/12.2 database compatibility cannot be proven from a snapshot taken
  from a 19c database engine.
- ODI repository structures are reference evidence, not an Akış persistence
  schema specification.

## 8. Canonical local artifact

For future programmatic lookup, use:

```text
.data/oracle-metadata/odi12c-repository-2026-09-12/inventory.json
```

It contains one record per table with ordered columns, grouped constraints and FK
targets, indexes with ordered columns, LOB metadata, coverage declarations and a
SHA-256 manifest of every supplied source file. Re-scan the original archive only
if a new export has a different source hash or adds a previously missing dataset.
