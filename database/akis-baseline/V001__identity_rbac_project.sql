CREATE SCHEMA akis;
SET search_path TO akis, public;

CREATE TABLE app_user (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    display_name VARCHAR(200) NOT NULL,
    email VARCHAR(320),
    disabled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_app_user_display_name
        CHECK (btrim(display_name) <> ''),
    CONSTRAINT ck_app_user_email
        CHECK (email IS NULL OR btrim(email) <> ''),
    CONSTRAINT ck_app_user_updated_at
        CHECK (updated_at >= created_at),
    CONSTRAINT ck_app_user_version
        CHECK (version >= 0)
);

CREATE UNIQUE INDEX uq_app_user_email
    ON app_user (lower(email))
    WHERE email IS NOT NULL;

CREATE TABLE external_identity (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    provider_type VARCHAR(20) NOT NULL,
    issuer VARCHAR(500),
    subject VARCHAR(500) NOT NULL,
    last_login_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_external_identity_provider
        CHECK (provider_type IN ('OIDC', 'LOCAL')),
    CONSTRAINT ck_external_identity_subject
        CHECK (btrim(subject) <> ''),
    CONSTRAINT ck_external_identity_issuer
        CHECK (
            (provider_type = 'OIDC' AND issuer IS NOT NULL AND btrim(issuer) <> '')
            OR (provider_type = 'LOCAL' AND issuer IS NULL)
        ),
    CONSTRAINT uq_external_identity
        UNIQUE NULLS NOT DISTINCT (provider_type, issuer, subject)
);

CREATE TABLE role (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scope VARCHAR(20) NOT NULL,
    code VARCHAR(100) NOT NULL,
    name VARCHAR(200) NOT NULL,
    description VARCHAR(1000),
    built_in BOOLEAN NOT NULL DEFAULT FALSE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_role_scope
        CHECK (scope IN ('SYSTEM', 'PROJECT')),
    CONSTRAINT ck_role_code
        CHECK (code ~ '^[A-Z][A-Z0-9_]{0,99}$'),
    CONSTRAINT ck_role_name
        CHECK (btrim(name) <> ''),
    CONSTRAINT uq_role_scope_code
        UNIQUE (scope, code),
    CONSTRAINT uq_role_id_scope
        UNIQUE (id, scope)
);

CREATE TABLE permission (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scope VARCHAR(20) NOT NULL,
    code VARCHAR(100) NOT NULL,
    resource VARCHAR(100) NOT NULL,
    action VARCHAR(50) NOT NULL,
    description VARCHAR(1000),
    CONSTRAINT ck_permission_scope
        CHECK (scope IN ('SYSTEM', 'PROJECT')),
    CONSTRAINT ck_permission_code
        CHECK (code ~ '^[A-Z][A-Z0-9_]{0,99}$'),
    CONSTRAINT ck_permission_resource
        CHECK (resource ~ '^[A-Z][A-Z0-9_]{0,99}$'),
    CONSTRAINT ck_permission_action
        CHECK (action ~ '^[A-Z][A-Z0-9_]{0,49}$'),
    CONSTRAINT uq_permission_code
        UNIQUE (code)
);

CREATE TABLE role_permission (
    role_id UUID NOT NULL REFERENCES role(id) ON DELETE CASCADE,
    permission_id UUID NOT NULL REFERENCES permission(id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE project (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(100) NOT NULL,
    name VARCHAR(200) NOT NULL,
    description VARCHAR(2000),
    archived_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_project_code
        CHECK (code ~ '^[A-Z][A-Z0-9_]{0,99}$'),
    CONSTRAINT ck_project_name
        CHECK (btrim(name) <> ''),
    CONSTRAINT ck_project_updated_at
        CHECK (updated_at >= created_at),
    CONSTRAINT ck_project_version
        CHECK (version >= 0),
    CONSTRAINT uq_project_code
        UNIQUE (code)
);

CREATE TABLE project_membership (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    joined_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    suspended_at TIMESTAMPTZ,
    ended_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_project_membership_state
        CHECK (state IN ('ACTIVE', 'SUSPENDED', 'ENDED')),
    CONSTRAINT ck_project_membership_dates
        CHECK (
            (state = 'ACTIVE' AND suspended_at IS NULL AND ended_at IS NULL)
            OR (state = 'SUSPENDED' AND suspended_at IS NOT NULL AND ended_at IS NULL)
            OR (state = 'ENDED' AND ended_at IS NOT NULL)
        ),
    CONSTRAINT ck_project_membership_version
        CHECK (version >= 0),
    CONSTRAINT uq_project_membership
        UNIQUE (project_id, user_id),
    CONSTRAINT uq_project_membership_id_project_user
        UNIQUE (id, project_id, user_id)
);

CREATE TABLE user_role (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    role_id UUID NOT NULL,
    role_scope VARCHAR(20) NOT NULL,
    project_id UUID,
    granted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    granted_by UUID REFERENCES app_user(id),
    revoked_at TIMESTAMPTZ,
    revoked_by UUID REFERENCES app_user(id),
    CONSTRAINT fk_user_role_definition
        FOREIGN KEY (role_id, role_scope) REFERENCES role(id, scope),
    CONSTRAINT fk_user_role_project_membership
        FOREIGN KEY (project_id, user_id)
        REFERENCES project_membership(project_id, user_id),
    CONSTRAINT ck_user_role_scope
        CHECK (
            (role_scope = 'SYSTEM' AND project_id IS NULL)
            OR (role_scope = 'PROJECT' AND project_id IS NOT NULL)
        ),
    CONSTRAINT ck_user_role_revocation
        CHECK (
            (revoked_at IS NULL AND revoked_by IS NULL)
            OR (revoked_at IS NOT NULL AND revoked_at >= granted_at)
        )
);

CREATE UNIQUE INDEX uq_user_role_active_system
    ON user_role (user_id, role_id)
    WHERE project_id IS NULL AND revoked_at IS NULL;

CREATE UNIQUE INDEX uq_user_role_active_project
    ON user_role (user_id, role_id, project_id)
    WHERE project_id IS NOT NULL AND revoked_at IS NULL;

CREATE INDEX ix_external_identity_user ON external_identity(user_id);
CREATE INDEX ix_project_membership_user ON project_membership(user_id, state);
CREATE INDEX ix_user_role_project_user ON user_role(project_id, user_id)
    WHERE project_id IS NOT NULL AND revoked_at IS NULL;

INSERT INTO role(scope, code, name, description, built_in) VALUES
    ('SYSTEM', 'SYSTEM_ADMIN', 'System Administrator',
        'Manages global identities and platform policy.', TRUE),
    ('PROJECT', 'PROJECT_ADMIN', 'Project Administrator',
        'Manages project settings, members, connections and schemas.', TRUE),
    ('PROJECT', 'DEVELOPER', 'Developer',
        'Authors and validates integration definitions.', TRUE),
    ('PROJECT', 'OPERATOR', 'Operator',
        'Operates executions and schedules without editing definitions.', TRUE),
    ('PROJECT', 'RELEASE_APPROVER', 'Release Approver',
        'Reviews and approves runnable production versions.', TRUE),
    ('PROJECT', 'VIEWER', 'Viewer',
        'Reads project definitions, catalog and execution history.', TRUE);

INSERT INTO permission(scope, code, resource, action, description) VALUES
    ('SYSTEM', 'USER_MANAGE', 'USER', 'MANAGE', 'Creates and disables users and identities.'),
    ('SYSTEM', 'PROJECT_CREATE', 'PROJECT', 'CREATE', 'Creates projects.'),
    ('SYSTEM', 'SYSTEM_POLICY_MANAGE', 'SYSTEM_POLICY', 'MANAGE', 'Manages platform policy.'),
    ('PROJECT', 'PROJECT_READ', 'PROJECT', 'READ', 'Reads project details.'),
    ('PROJECT', 'PROJECT_MANAGE', 'PROJECT', 'MANAGE', 'Updates and archives a project.'),
    ('PROJECT', 'MEMBER_MANAGE', 'MEMBER', 'MANAGE', 'Manages project membership and roles.'),
    ('PROJECT', 'CONNECTION_READ', 'CONNECTION', 'READ', 'Reads connections and schemas.'),
    ('PROJECT', 'CONNECTION_MANAGE', 'CONNECTION', 'MANAGE', 'Creates connection revisions and schema mappings.'),
    ('PROJECT', 'CONNECTION_TEST', 'CONNECTION', 'TEST', 'Tests a connection revision.'),
    ('PROJECT', 'CATALOG_READ', 'CATALOG', 'READ', 'Reads discovered catalog metadata.'),
    ('PROJECT', 'CATALOG_DISCOVER', 'CATALOG', 'DISCOVER', 'Runs metadata discovery.'),
    ('PROJECT', 'DEFINITION_READ', 'DEFINITION', 'READ', 'Reads project definitions.'),
    ('PROJECT', 'DEFINITION_WRITE', 'DEFINITION', 'WRITE', 'Creates and edits project definitions.'),
    ('PROJECT', 'DEFINITION_VALIDATE', 'DEFINITION', 'VALIDATE', 'Validates project definitions.'),
    ('PROJECT', 'RUNNABLE_VERSION_READ', 'RUNNABLE_VERSION', 'READ', 'Reads immutable runnable versions.'),
    ('PROJECT', 'RUNNABLE_VERSION_CREATE', 'RUNNABLE_VERSION', 'CREATE', 'Submits an immutable runnable version.'),
    ('PROJECT', 'RUNNABLE_VERSION_APPROVE', 'RUNNABLE_VERSION', 'APPROVE', 'Approves a runnable production version.'),
    ('PROJECT', 'RUN_READ', 'RUN', 'READ', 'Reads executions and step details.'),
    ('PROJECT', 'RUN_START', 'RUN', 'START', 'Starts an authorized execution.'),
    ('PROJECT', 'RUN_CANCEL', 'RUN', 'CANCEL', 'Requests execution cancellation.'),
    ('PROJECT', 'RUN_RETRY', 'RUN', 'RETRY', 'Retries an eligible execution.'),
    ('PROJECT', 'SCHEDULE_MANAGE', 'SCHEDULE', 'MANAGE', 'Creates and updates schedules.');

WITH grants(role_code, permission_code) AS (
    VALUES
        ('SYSTEM_ADMIN', 'USER_MANAGE'),
        ('SYSTEM_ADMIN', 'PROJECT_CREATE'),
        ('SYSTEM_ADMIN', 'SYSTEM_POLICY_MANAGE'),

        ('PROJECT_ADMIN', 'PROJECT_READ'),
        ('PROJECT_ADMIN', 'PROJECT_MANAGE'),
        ('PROJECT_ADMIN', 'MEMBER_MANAGE'),
        ('PROJECT_ADMIN', 'CONNECTION_READ'),
        ('PROJECT_ADMIN', 'CONNECTION_MANAGE'),
        ('PROJECT_ADMIN', 'CONNECTION_TEST'),
        ('PROJECT_ADMIN', 'CATALOG_READ'),
        ('PROJECT_ADMIN', 'CATALOG_DISCOVER'),
        ('PROJECT_ADMIN', 'DEFINITION_READ'),

        ('DEVELOPER', 'PROJECT_READ'),
        ('DEVELOPER', 'CONNECTION_READ'),
        ('DEVELOPER', 'CONNECTION_TEST'),
        ('DEVELOPER', 'CATALOG_READ'),
        ('DEVELOPER', 'CATALOG_DISCOVER'),
        ('DEVELOPER', 'DEFINITION_READ'),
        ('DEVELOPER', 'DEFINITION_WRITE'),
        ('DEVELOPER', 'DEFINITION_VALIDATE'),
        ('DEVELOPER', 'RUNNABLE_VERSION_READ'),
        ('DEVELOPER', 'RUNNABLE_VERSION_CREATE'),
        ('DEVELOPER', 'RUN_READ'),

        ('OPERATOR', 'PROJECT_READ'),
        ('OPERATOR', 'CONNECTION_READ'),
        ('OPERATOR', 'CATALOG_READ'),
        ('OPERATOR', 'DEFINITION_READ'),
        ('OPERATOR', 'RUNNABLE_VERSION_READ'),
        ('OPERATOR', 'RUN_READ'),
        ('OPERATOR', 'RUN_START'),
        ('OPERATOR', 'RUN_CANCEL'),
        ('OPERATOR', 'RUN_RETRY'),
        ('OPERATOR', 'SCHEDULE_MANAGE'),

        ('RELEASE_APPROVER', 'PROJECT_READ'),
        ('RELEASE_APPROVER', 'CATALOG_READ'),
        ('RELEASE_APPROVER', 'DEFINITION_READ'),
        ('RELEASE_APPROVER', 'RUNNABLE_VERSION_READ'),
        ('RELEASE_APPROVER', 'RUNNABLE_VERSION_APPROVE'),
        ('RELEASE_APPROVER', 'RUN_READ'),

        ('VIEWER', 'PROJECT_READ'),
        ('VIEWER', 'CONNECTION_READ'),
        ('VIEWER', 'CATALOG_READ'),
        ('VIEWER', 'DEFINITION_READ'),
        ('VIEWER', 'RUNNABLE_VERSION_READ'),
        ('VIEWER', 'RUN_READ')
)
INSERT INTO role_permission(role_id, permission_id)
SELECT r.id, p.id
  FROM grants g
  JOIN role r ON r.code = g.role_code
  JOIN permission p ON p.code = g.permission_code;
