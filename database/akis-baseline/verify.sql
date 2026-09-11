SET search_path TO akis, public;

DO $$
DECLARE
    actual BIGINT;
BEGIN
    SELECT COUNT(*) INTO actual
      FROM information_schema.tables
     WHERE table_schema = 'akis' AND table_type = 'BASE TABLE';
    IF actual <> 8 THEN
        RAISE EXCEPTION 'Expected 8 Akis tables, found %', actual;
    END IF;

    SELECT COUNT(*) INTO actual FROM role;
    IF actual <> 6 THEN
        RAISE EXCEPTION 'Expected 6 built-in roles, found %', actual;
    END IF;

    SELECT COUNT(*) INTO actual FROM permission;
    IF actual <> 22 THEN
        RAISE EXCEPTION 'Expected 22 permissions, found %', actual;
    END IF;

    SELECT COUNT(*) INTO actual FROM app_user;
    IF actual <> 0 THEN
        RAISE EXCEPTION 'Business identity data must start empty.';
    END IF;

    SELECT COUNT(*) INTO actual FROM project;
    IF actual <> 0 THEN
        RAISE EXCEPTION 'Project data must start empty.';
    END IF;
END
$$;

BEGIN;

INSERT INTO app_user(id, display_name)
VALUES ('00000000-0000-0000-0000-000000000001', 'Contract User');

INSERT INTO project(id, code, name)
VALUES ('00000000-0000-0000-0000-000000000002', 'CONTRACT', 'Contract Project');

INSERT INTO project_membership(id, project_id, user_id)
VALUES (
    '00000000-0000-0000-0000-000000000003',
    '00000000-0000-0000-0000-000000000002',
    '00000000-0000-0000-0000-000000000001');

DO $$
DECLARE
    system_role_id UUID;
    project_role_id UUID;
BEGIN
    SELECT id INTO system_role_id FROM role WHERE code = 'SYSTEM_ADMIN';
    SELECT id INTO project_role_id FROM role WHERE code = 'DEVELOPER';

    BEGIN
        INSERT INTO user_role(user_id, role_id, role_scope, project_id)
        VALUES (
            '00000000-0000-0000-0000-000000000001',
            system_role_id,
            'SYSTEM',
            '00000000-0000-0000-0000-000000000002');
        RAISE EXCEPTION 'SYSTEM role with project_id was accepted.';
    EXCEPTION
        WHEN check_violation THEN NULL;
    END;

    BEGIN
        INSERT INTO user_role(user_id, role_id, role_scope)
        VALUES (
            '00000000-0000-0000-0000-000000000001',
            project_role_id,
            'PROJECT');
        RAISE EXCEPTION 'PROJECT role without project_id was accepted.';
    EXCEPTION
        WHEN check_violation THEN NULL;
    END;

    INSERT INTO user_role(user_id, role_id, role_scope)
    VALUES (
        '00000000-0000-0000-0000-000000000001',
        system_role_id,
        'SYSTEM');

    INSERT INTO user_role(user_id, role_id, role_scope, project_id)
    VALUES (
        '00000000-0000-0000-0000-000000000001',
        project_role_id,
        'PROJECT',
        '00000000-0000-0000-0000-000000000002');
END
$$;

ROLLBACK;

SELECT 'AKIS_BASELINE_OK' AS verification_result;
