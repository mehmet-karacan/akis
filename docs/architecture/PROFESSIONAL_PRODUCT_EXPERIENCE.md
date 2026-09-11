# Akış Professional Product Experience

Status: **Accepted direction / incremental implementation**  
Scope: Web information architecture, project home, terminology, Oracle connection UX and accessibility baseline.

## 1. Product promise

Akış is an enterprise integration control plane. Its interface must make four things immediately clear:

1. where an integration object belongs,
2. whether it is ready to publish or run,
3. what ran most recently and what needs attention,
4. which environment and connection a physical operation will use.

The UI must never manufacture operational confidence. Cards and counters are shown only when backed by real API data. Empty projects receive a guided setup path; projects with run history receive an operational home.

## 2. Information architecture

The primary project navigation is grouped by user intent:

| Group | Modules |
| --- | --- |
| Project | Overview |
| Configure | Topology, Connections, Schemas and environments, Bindings |
| Data | Models and catalog |
| Design | Project explorer, Mappings, Procedures, Packages, Load plans, Library |
| Operate | Publications, Runs, later Schedules |
| Administration | Team and access |

The first UI slice keeps existing route boundaries and groups them as **Configure**, **Design**, **Operate** and **Administration**. More granular routes are introduced only with matching backend contracts.

Folders do not become permanent primary-navigation entries. The Design route owns a second rail called **Project explorer**:

```text
Project explorer
├─ Folders
│  └─ <user folders>
├─ Mappings
├─ Procedures
├─ Packages
├─ Load plans
└─ Library
   ├─ Reusable mappings
   ├─ Variables
   ├─ Sequences
   ├─ User functions
   └─ Knowledge modules
```

This tree is a navigator, not the source of truth. Every item is addressable by a stable UUID and deep link. Moving an item changes folder membership, not its identity or history.

## 3. Adaptive project home

### New or empty project

Show a calm readiness path:

1. create and test a connection,
2. discover or define models,
3. create a mapping or multi-step procedure,
4. validate, approve and publish,
5. run and observe.

No zero-valued decorative metrics are shown.

### Project with run history

Show an operational overview with:

- counts for running, successful and attention-required runs,
- recent runs ordered by creation time,
- start type, status and timestamp,
- direct links to the run event journal,
- a prominent warning for failed, uncertain or intervention-required work.

The initial implementation may derive these values from the existing run-list endpoint. A later aggregate endpoint should replace client-side derivation when volume requires paging and server-side summaries.

## 4. Oracle connection definition V2

Connection UX is database-aware. Choosing Oracle reveals an Oracle-specific mode and policy form; it must not expose arbitrary driver or provider configuration.

```json
{
  "databaseType": "ORACLE",
  "mode": "JDBC",
  "policyVersion": 2,
  "jdbc": {
    "host": "db.example.internal",
    "port": 1521,
    "connectIdentifier": {
      "type": "SERVICE_NAME",
      "value": "SERVICE"
    },
    "transport": "TCP",
    "credentialSecretReferenceUuid": "<uuid>"
  },
  "executionPolicy": {
    "connectTimeoutMs": 10000,
    "readTimeoutMs": 60000,
    "queryTimeoutSeconds": 300,
    "keepAlive": true
  }
}
```

Rules:

- Mode is exactly one of `JDBC` or `JNDI`.
- The Oracle JDBC driver is server-pinned for the selected platform version; users do not edit the driver class.
- JDBC uses exactly one connect identifier: `SERVICE_NAME` or `SID`. Service name is the default.
- `TCP` and `TCPS` are explicit. `TCPS` requires a verification and trust-source policy.
- JNDI accepts only locally administered names under an allowlist such as `java:comp/env/jdbc/`. Remote LDAP/RMI providers are forbidden.
- Credentials, wallets and client certificates are typed secret references. Values, provider locators and local paths are never exported.
- Create, test, discovery and runtime use one shared validator/resolver to prevent policy drift.
- A version is saved as draft, tested, then activated. The test result belongs to that immutable revision.
- Connection tests must be protected against SSRF and return stable, sanitized error codes.

Bundle V2 exports connection requirements and topology references, but never secret values. Import requires explicit local secret mappings; unresolved connections remain drafts.

## 5. Language, terminology and casing

English is the default locale; Turkish is fully supported and the preference persists. Every visible string—including empty states, errors, enum labels, tooltips and accessible names—uses the same localization layer.

- Buttons use sentence case: **Create connection**, **Test connection**, **Cancel** / **Bağlantı oluştur**, **Bağlantıyı test et**, **İptal**.
- Product acronyms preserve canonical uppercase: Oracle, JDBC, JNDI, OCI, SQL, PL/SQL, DML, DDL, UUID, JSON, TLS, SID.
- Internal enum values such as `READ_ONLY`, `SOURCE`, `STOP` and `URETIM` are never rendered directly.
- `Cancel / İptal` abandons an edit; `Close / Kapat` dismisses informational content.
- Preferred Turkish terms include **Eşleme**, **Saklı prosedür**, **Sekans**, **Metaveri**, **Anlık görüntü**, **Kaynak**, **Hedef**, **Etkin** and **Ayrıntılar**.
- Dates use one locale mapping consistently: `en-US` and `tr-TR`.

## 6. Interaction and accessibility baseline

- Every icon-only button has an accessible name.
- Navigation collapse state uses `aria-expanded` and a localized label.
- Dialogs trap focus, close with Escape, restore focus and have labelled titles.
- Tabs use tab/list semantics and keyboard navigation.
- Validation messages are connected to their fields with `aria-describedby`.
- Status is conveyed with text in addition to color.
- Destructive and external-impact actions require specific labels; generic **Confirm** is avoided.
- Loading, error, empty and retry states are distinct. An error must never leave stale “Loading…” text visible.

## 7. Delivery sequence

1. **Navigation and home:** grouped project navigation, adaptive project home, localized shell controls.
2. **Design explorer:** folders, stable deep links, breadcrumbs, search, move/archive contracts; migrate definitions from a flat list.
3. **Oracle connection V2:** persistence migration, shared validation, JDBC/JNDI wizard, revision-bound test lifecycle and bundle V2.
4. **Operational cockpit:** aggregate metrics, paging, filters, schedules and intervention queues.
5. **Accessibility and copy gate:** automated checks plus EN/TR glossary review in CI when the product is ready to enable CI.

## 8. Safety constraints during development

- Execution feature flags remain disabled until the runtime acceptance checklist is complete.
- UI work does not authorize Oracle DDL or DML.
- The dashboard never implies an object is runnable only because its publication is active; runtime capability must also be executable.
- CI/CD remains disabled until explicitly enabled by the project owner.

## 9. Implementation status

As of 2026-09-11:

- grouped project navigation and the adaptive project home are implemented,
- Design uses a project-explorer rail backed by the existing relational folder hierarchy,
- nested folder creation, definition placement and folder moves are project-scoped and optimistic-lock protected,
- V015 prevents hierarchy cycles and enforces the 100-level bundle compatibility limit,
- definition selection is deep-linkable with the `definition` query parameter,
- unsaved draft switching is guarded and stale definition responses cannot overwrite a newer selection,
- route-level code splitting keeps the initial application bundle independent from the large design and topology workspaces.
- V016/V017 preserve existing SKY/GPU connection-version identities while adding immutable JDBC/JNDI mode invariants,
- Oracle JDBC drivers are server-pinned; local-only JNDI DataSources are supported for test/discovery while execution remains fail-closed until an immutable target fingerprint is enforced,
- the connection-version UI is a localized JDBC/JNDI wizard with explicit Service Name/SID, TCP-only creation until verified TCPS is delivered, active ENV secret-reference-only credentials, review, create and revision-specific test actions.

The append-only connection test journal, explicit draft-to-active transition, advanced verified TCPS wallet/certificate support and portable Bundle V2 connection requirements remain the next Oracle Connection V2 delivery slice. Existing Bundle V1 remains unchanged and sanitized.

Folder archive/delete is intentionally deferred. Its non-empty-folder and subtree behavior requires an explicit product decision; no silent cascade is permitted.

## References

- [Apache Airflow UI](https://airflow.apache.org/docs/apache-airflow/stable/ui.html)
- [Oracle Data Integrator Designer Navigator](https://docs.oracle.com/middleware/12212/odi/ODISH/f1_designer_navigator.htm)
- [Oracle Data Integrator projects and folders](https://docs.oracle.com/en/middleware/fusion-middleware/data-integrator/12.2.1.3/using/projects.html)
- [Oracle Data Integrator packages](https://docs.oracle.com/en/middleware/fusion-middleware/data-integrator/12.2.1.4/odidg/creating-and-using-packages.html)
- [Oracle JDBC data sources and URLs for 19c](https://docs.oracle.com/en/database/oracle/oracle-database/19/jjdbc/data-sources-and-URLs.html)
- [Oracle Database 19c TLS](https://docs.oracle.com/en/database/oracle/oracle-database/19/dbseg/tls-and-oracle-database.html)
