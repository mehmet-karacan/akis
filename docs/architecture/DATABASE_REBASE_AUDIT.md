# Akış Database Rebase Audit

Status: **Accepted direction; clean `akis` baseline in progress; no destructive cutover has been performed**
Date: 2026-09-12

## 1. Recommendation

Rebase the control database now, before more UI and runtime features depend on
the current physical schema. Do not mutate or drop the existing database first.
Build and test the replacement baseline in a separate local database, migrate the
small set of durable configuration records, and switch only after contract tests
pass.

The rebase should simplify names and lifecycle representation without weakening
the execution safety model. A boolean is appropriate only for a genuinely binary,
orthogonal fact. Workflow state, audit evidence and stable machine identifiers
must not be converted to booleans.

## 2. Current measured state

The local PostgreSQL database currently contains:

| Measure | Current value |
| --- | ---: |
| Application tables | 65 |
| Application columns | 832 |
| Columns named `kod` or ending `_kodu` | 81 |
| CHECK constraints | 147 |
| PostgreSQL enum types | 0 |
| Stored functions | 55 |
| Triggers | 88 |
| Tables with both `id` and `uuid` | 63 |
| Populated tables | 46 |
| Empty tables | 19 |
| Estimated application rows | 441 |
| Flyway migrations | 20 |

The database is still small enough to rebase. It already contains valuable pilot
evidence: projects, two connection/schema paths, definitions, releases, runs,
step evidence, run events and audit events. Those records must be exported or
recreated as explicit acceptance fixtures before the old database is retired.

## 3. What is actually wrong

### 3.1 Mixed technical language

Table and column names are Turkish while several stored values and Java/API
contracts are English. Examples include Turkish `durum_kodu` columns containing
both `AKTIF` and `ACTIVE` in different tables. This increases translation code and
makes cross-module contracts harder to read.

Recommendation: use English technical identifiers in PostgreSQL, Java and API
contracts. Localize only the UI. Use `state`, `type`, `role`, `risk`, `result` and
`error_code` instead of adding `_kodu` to every discriminator.

### 3.2 Status used for unrelated concepts

The same `durum_kodu` pattern represents four different things:

1. a binary availability flag,
2. archival or termination history,
3. a multi-state workflow,
4. an execution state machine.

These require different data models. A universal status column is not a domain
model.

### 3.3 Identity concepts are coupled

`kullanici` contains OIDC issuer/provider and subject directly. The issuer and
subject pair is not redundant: OIDC subject is opaque and only unique within an
issuer. It is the correct external login key. The coupling is wrong because:

- a user profile may have more than one external identity,
- local Basic authentication is currently represented as a synthetic OIDC
  provider,
- profile lifecycle and login-provider lifecycle cannot be managed separately.

Recommendation:

```text
app_user
├─ id (UUID primary key)
├─ display_name
├─ email
├─ disabled_at
└─ created_at / updated_at

external_identity
├─ id (UUID primary key)
├─ user_id
├─ provider_type        OIDC | LOCAL
├─ issuer               nullable only for LOCAL
├─ subject              opaque provider identifier
├─ last_login_at
└─ unique(provider_type, issuer, subject)
```

No password hash or secret value belongs in `app_user`. Local credentials, if
retained for development, require a separate restricted record or environment
configuration.

### 3.4 Role-based access control stays first-class

Role and permission tables are required. The cleanup must remove duplication,
not authorization. The current database splits system and project roles across
multiple parallel table families. The clean `akis` baseline should expose one
consistent RBAC model:

```text
role
├─ id (UUID primary key)
├─ scope                  SYSTEM | PROJECT
├─ code                   stable machine identifier
├─ name
├─ built_in
└─ enabled

permission
├─ id (UUID primary key)
├─ code                   stable machine identifier
├─ scope
├─ resource
└─ action

role_permission
├─ role_id
└─ permission_id

user_role
├─ user_id
├─ role_id
├─ project_id             required for PROJECT, null for SYSTEM
├─ granted_at / granted_by
└─ revoked_at / revoked_by
```

`project_member` remains the explicit user–project relationship. A project role
assignment must belong to an active project membership. `user_role` grants the
role; it does not replace the membership lifecycle or its audit history.

Recommended built-in project roles:

| Role code | Responsibility | Explicit exclusions |
| --- | --- | --- |
| `PROJECT_ADMIN` | Project settings, membership, connections and schemas | No implicit production approval or execution |
| `DEVELOPER` | Interface/mapping/procedure/package authoring and validation | Cannot approve own release or operate production by default |
| `OPERATOR` | Start, cancel, retry and inspect executions; manage schedules | Cannot modify definition content |
| `RELEASE_APPROVER` | Review and approve runnable production versions | Cannot silently change the submitted version |
| `VIEWER` | Read definitions, catalog and execution history | No mutation or execution |

The system role `SYSTEM_ADMIN` manages global identities and platform policy but
does not automatically bypass project or production permissions. Emergency
break-glass access, if added later, must be explicit, time-limited and audited.

Separation-of-duty rules belong in backend authorization and database invariants,
not only in hidden buttons:

- a developer cannot approve the exact production release they submitted,
- an operator cannot modify the immutable version being executed,
- project administration does not imply production execution,
- revoked role assignments stop authorizing immediately while their history is
  retained,
- API authorization resolves the external identity to `app_user` once, then uses
  user/project/role relations instead of joining OIDC issuer and subject through
  every permission query.

### 3.5 Too many speculative tables in the baseline

The following empty tables have no direct backend source reference and no stored
function reference in the current database:

```text
baglanti_yetkisi
degisken_deger_olayi
hata_ozeti
metrik
olay_kutusu
sekans_deger_olayi
sekans_durumu
sema_gocu
tanim_bagimliligi
tanim_gorunumu
veri_soyu_olayi
zamanlama
```

They represent plausible future capabilities, but they should not exist in the
new baseline until an application contract uses them. `alt_model`, membership
and system-role tables are currently empty but are referenced by backend code and
therefore require a separate product/code decision rather than automatic removal.

`calistirma_gorevi`, `kontrol_noktasi` and `pilot_yayin_niyeti` may have no current
rows or direct Java references but are used by stored execution functions. They
are safety/runtime structures and must not be removed based only on row count.

## 4. Lifecycle representation rules

| Domain fact | Recommended representation | Reason |
| --- | --- | --- |
| User can sign in | `disabled_at TIMESTAMPTZ NULL` | Preserves when and whether access was disabled |
| Project archived | `archived_at TIMESTAMPTZ NULL` | Archive is a historical event, not a generic status |
| Folder/definition archived | `archived_at TIMESTAMPTZ NULL` | Supports restore and audit |
| Static role enabled | `enabled BOOLEAN` | Exactly two independent states |
| Constraint enabled in snapshot | `enabled BOOLEAN` | Captured binary database fact |
| Connection revision lifecycle | constrained `state` | DRAFT → TESTED → ACTIVE is a workflow |
| Membership lifecycle | constrained `state` plus timestamps | ACTIVE, SUSPENDED and ENDED are different facts |
| Release approval | constrained `decision` | Pending/approved/rejected is not binary |
| Run lifecycle | constrained `state` | Queued/running/succeeded/failed/cancelled/uncertain need transitions |
| Worker/target lease | constrained `state` plus lease fields | Ownership and fencing depend on exact state |
| API/runtime error | stable `error_code` | Required for localization, retry policy and automation |

For evolving workflow state, prefer a clearly named text column with a CHECK
constraint and a matching Java enum. PostgreSQL native enum types make frequent
state evolution and rollback unnecessarily rigid. Boolean columns must be named
as predicates such as `enabled`, `read_only` or `requires_approval`.

## 5. Rules for `code`

Keep a human-readable stable `code` or `slug` only when users, imports, policies
or integrations need a durable reference independent of the display name:

- project code,
- connection code,
- logical schema code,
- environment code,
- definition code,
- role and permission code,
- stable error/event/operation code.

Do not add a code to pure join rows, immutable evidence rows, generated run steps,
snapshots or internal child records where UUID plus parent and ordinal already
form the identity. Never expose internal enum values directly in the localized UI.

## 6. Identifier strategy

The current schema gives 63 of 65 tables both a BIGINT `id` and a UUID. This is
not automatically wrong, but it adds mapping and constraint noise everywhere.

Recommended baseline rule:

- metadata/control aggregates use UUID as their primary and foreign key,
- high-volume append-only event/journal tables may use BIGINT identity keys for
  storage locality while retaining the parent run UUID,
- do not give every join/evidence row a second public UUID unless it is directly
  addressable through the API,
- use a numeric sequence only where ordering or high-volume ingestion needs it.

## 7. Clean baseline module boundary

The clean baseline should be organized by domain rather than by one 60 KB initial
migration followed by corrective migrations.

### Identity and access

- `app_user`
- `external_identity`
- `role`, `permission`, `role_permission`, `user_role`
- `project_member`

The role model covers both system and project scopes. Project-scoped assignments
carry `project_id`; system-scoped assignments do not. Built-in Developer,
Operator, Release Approver, Viewer and Project Admin roles are seeded once rather
than duplicated as one physical role row per project.

### Projects and definitions

- `project`, `folder`
- `definition`, `definition_draft`, `definition_version`
- dependencies only when package/interface compilation actually consumes them

Variables and sequences should initially use typed definitions unless their
runtime state requires a dedicated table.

### Connections and catalog

- `connection`, `connection_version`, `connection_test`
- internal secret binding; no secret-reference management screen
- `physical_schema`, `logical_schema`, `environment`, `schema_binding`
- `data_model`, `data_object`
- `schema_snapshot`, `schema_column`, `schema_constraint`,
  `schema_constraint_column`
- `definition_data_binding`

### Runnable versions and execution

- immutable scenario/release and approval records
- `run`, typed `run_step`, append-only `run_event`
- idempotency, worker lease, target fence, publish intent and reconciliation
  evidence required by the accepted runtime safety contract

Metrics, lineage, scheduling, outbox and generalized error-summary tables are
added only with a consuming API and acceptance test.

## 8. Rebase delivery plan

1. Freeze schema feature work; UI research and read-only analysis may continue.
2. Produce an old-table → clean-baseline table/field mapping and mark every field
   `KEEP`, `RENAME`, `DERIVE`, `DEFER` or `DROP`.
3. Create the baseline in a separate temporary database such as
   `akis_metadata_clean`; never
   overwrite the current volume during development.
4. Split baseline migrations by the module boundaries above. Preserve critical
   runtime invariants and fail-closed functions with focused tests.
5. Update repositories and API contracts module by module.
6. Export durable local configuration without secret values: active project,
   connection definitions, schema mappings, catalog bindings and definition
   versions. Preserve the successful pilot as a sanitized test fixture or archive.
7. Run all migration, repository, service, API, bundle, execution and frontend
   contract tests against a clean database containing the `akis` schema.
8. Switch local configuration to the clean baseline only after acceptance passes.
9. Delete the old database/volume only after explicit user approval and after the
   backup hash and restore check are recorded.

## 9. Acceptance gates

- A clean database can be created from the clean migrations only.
- No migration contains real credentials or local connection values.
- Local Basic and OIDC identities no longer share misleading columns.
- Developer, Operator and Release Approver permissions are separated and enforced
  server-side.
- `role`, `permission`, `role_permission` and `user_role` have one system/project
  scope contract; project assignments require membership.
- Binary fields are boolean/timestamp facts; workflow states remain explicit.
- Status/type/error values have one language and one Java enum contract.
- The normal UI never shows raw state codes, UUID input or secret references.
- Project import/export does not contain secret values or local provider locators.
- The controlled Oracle pilot and its reconciliation/fencing tests still pass.
- CI/CD and GitHub workflows remain disabled.

## 10. Decision needed before destructive cutover

Before retiring the current database, confirm that it is local-development-only
and that no other developer, test environment or external deployment uses these
20 Flyway migrations as an applied history. The clean baseline can be designed
and tested without this confirmation; deleting or replacing the current database
cannot.
