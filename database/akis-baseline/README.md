# Akis clean baseline

This directory contains the clean replacement baseline for the local PostgreSQL
control database. The final database schema is named `akis` and the baseline has
no separate version namespace or label.

The first migration deliberately contains only identity, RBAC and project
membership. Business tables start empty. Built-in roles and permissions are
reference data and are seeded by the migration.

## First group

```text
app_user
external_identity
role
permission
role_permission
project
project_membership
user_role
```

`role` is a reusable system/project role definition. `project_membership` is the
user-to-project relationship. `user_role` assigns a system role directly to a
user or a project role to a user within a project. Composite foreign keys ensure
that a project-scoped assignment has a matching project membership.

The existing `entegrasyon` schema remains only as a temporary rollback source
while application repositories are moved group by group. It must not receive new
schema features. Once every group passes clean-database acceptance tests, these
baseline files replace the old Flyway chain and the old schema is retired.

No bootstrap user or credential is stored in this migration. The first
administrator will be created through a separate, explicit local bootstrap flow.

## Verification

With the local PostgreSQL container running, execute:

```powershell
.\database\akis-baseline\test-baseline.ps1
```

The script creates a uniquely named temporary database, applies the clean
migration, runs positive and negative integrity checks, and removes only that
temporary database. It does not modify the development database or contact an
Oracle system.
