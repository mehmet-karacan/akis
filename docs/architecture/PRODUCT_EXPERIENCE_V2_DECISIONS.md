# Akış Product Experience V2 Decisions

Status: **Accepted direction; implementation waits for Oracle 12c metadata review**
Date: 2026-09-11

This document supersedes the earlier navigation and overview decisions in
`PROFESSIONAL_PRODUCT_EXPERIENCE.md`. It records the product direction before
UI and API contracts are changed.

## 1. Mandatory project context

- `/projects` is a project gate, not a working dashboard.
- After sign-in, the last accessible project opens automatically. When there is
  no valid last project, the user must select or create one.
- A persistent project switcher allows changing the project later.
- Project routes load their project through one shared project context. An
  invalid or unauthorized project never renders stale project navigation.

## 2. Project landing page

- The first version is a calm entry page only.
- Operational metrics, setup cards, detailed status, safety banners and recent
  run tables are not shown here yet.
- Project status is shown only when it requires attention or in project details.
- Export is under the project actions menu or Project Settings, never a primary
  page action.

## 3. Navigation and project explorer

Project work is split into broad workspaces rather than a long grouped sidebar:

1. **Development**: persistent project folder tree containing Interfaces,
   Mappings, Procedures, Packages, Variables and Sequences.
2. **Operations**: executions, schedules and intervention/approval work.
3. **Connections**: providers, connection revisions, physical schemas,
   logical schemas and environment mappings.

The labels “Configure / Design / Operate” are removed from the primary sidebar.
Every project object has a stable UUID and deep link. JSON is not a workspace.

## 4. Connections and schemas

The user-facing term “Topology” is replaced with **Connections**.

- The left tree is provider-first: for example `Oracle > SKY > physical schemas`.
- The main pane displays connection cards and the selected item detail.
- **New Connection** opens a drawer: provider, JDBC/JNDI mode, Oracle-specific
  fields, policy, test and activation.
- Logical schemas and environment-to-physical-schema mappings are managed next
  to the connection tree, without copying connection data into definitions.
- “Secret references” are removed from the UI. Passwords remain protected by
  backend secret indirection; secret values and locators are never returned or
  exported.
- Timeout belongs to an immutable connection policy, not to procedure steps.

## 5. Procedure editor and runtime contract

The normal editor is structured and step-based. A step shows:

- name and ordinal,
- operation type,
- source or target role,
- selected connection and its provider,
- logical schema and the resolved physical schema,
- a role-specific SQL editor,
- error behavior in advanced settings.

Source SELECT and target commands are visually and contractually separate.
Cross-database movement uses a bounded rowset between two steps; it is not a
single cross-database SQL statement. Provider, credentials, connection revision,
physical schema and timeout are resolved from connection/schema bindings and are
not duplicated in the step definition.

The current runtime supports a controlled Oracle V1 subset: source SELECT,
target INSERT, TRUNCATE and approved statistics gathering. General free SQL or
PL/SQL requires a separately versioned V2 policy/parser/approval contract. The UI
must not promise capabilities that the runtime rejects.

## 6. Runnable versions instead of Publications

“Publications” is removed from primary navigation. The immutable backend release
record remains because it pins hashes, approvals, environment bindings and
physical manifests.

Users see **Make Runnable** and **Runnable Version** in the object context. Raw
UUIDs, hashes and manifests live under advanced/audit details. Run creation uses
definition and environment selection; the server resolves exactly one active
immutable release and fails closed otherwise.

## 7. Execution explorer

Operations uses a tree and a detail pane:

```text
Execution
└─ Procedure / Package / Interface
   └─ Run
      ├─ Step 1
      ├─ Step 2
      └─ Step n
```

Step details show status, timing, row/byte counts, sanitized error information,
resolved connection/schema names and command hash. Raw credentials and SQL text
are not journal output. The backend must expose a typed run-step projection; the
frontend must not infer steps from arbitrary event JSON.

## 8. Copy, localization and JSON placement

- English is the default locale and Turkish is fully supported.
- Turkish action labels use title case consistently, for example **Projeyi Dışa
  Aktar**. Canonical acronyms such as SQL, JSON, JDBC, JNDI and SID stay uppercase.
- Visible enum values never leak backend codes.
- Import/export lives in Project Settings and object context menus.
- Raw JSON is available only under **Advanced > View Raw Definition** for
  troubleshooting; ordinary editing uses forms, trees and SQL editors.

## 9. Required backend contracts before the final UI

1. Align definition validation with runtime task/rowset limits.
2. Remove step timeout ownership and journal the effective connection timeout.
3. Add editor context with logical schema, physical schema, provider and mode.
4. Add a typed run-steps endpoint.
5. Add a runnable-definition/run facade that hides publication UUIDs.
6. Review Oracle 12c metadata output before finalizing type, object and schema
   discovery contracts.

CI/CD and workflow execution remain disabled by default. Local runtime flags are
not committed.

## 10. Visual identity and administration order

- Connections use compact provider cards. Oracle, PostgreSQL, MySQL and other
  providers have a recognizable provider color and monogram while their text
  label remains visible and accessible.
- Project contents remain a scalable folder tree instead of a wall of cards.
  Folders use folder icons; Mapping, Package, Procedure, Variable, Sequence and
  the other definition types each use a stable Lucide icon and type label.
- Color and icons are supporting cues only. Selection, status and object type
  are always expressed in text as well.
- Role and permission assignment screens are deliberately scheduled after the
  project, connection/schema, definition and execution workflows. Existing
  server-side authorization and database integrity controls remain mandatory
  throughout development.
