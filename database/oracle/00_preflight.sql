-- A completed installation of this exact contract may be rerun. An incomplete,
-- older, newer or colliding installation is deliberately not upgraded in place.

DECLARE
    c_component CONSTANT VARCHAR2(30) := 'AKIS_LEDGER';
    c_version   CONSTANT NUMBER := 1;
    c_hash      CONSTANT VARCHAR2(64) :=
        '6fce5297df31bd700711a62af2e37a5305fdfb573f0828db83aa4e407f401c9c';
    v_marker_tables NUMBER;
    v_managed_objects NUMBER;
    v_matching_markers NUMBER;
    v_required_tables NUMBER;
    v_valid_package_objects NUMBER;
    v_package_api NUMBER;
    v_package_authid NUMBER;
    v_enabled_triggers NUMBER;
    v_valid_trigger_objects NUMBER;
BEGIN
    IF DBMS_DB_VERSION.VERSION <> 19 THEN
        RAISE_APPLICATION_ERROR(
            -20069,
            'This ledger contract requires Oracle Database 19c');
    END IF;

    SELECT COUNT(*) INTO v_marker_tables
      FROM USER_TABLES
     WHERE TABLE_NAME = 'ETL_KURULUM_SURUMU';

    IF v_marker_tables = 0 THEN
        SELECT COUNT(*) INTO v_managed_objects
          FROM USER_OBJECTS
         WHERE OBJECT_NAME IN (
                   'ETL_KURULUM_SURUMU', 'ETL_YUKLEME_KILIDI',
                   'ETL_YUKLEME_DEFTERI', 'ETL_YAYIN_DEFTERI',
                   'ETL_KANIT_PKG', 'ETL_KS_IMM_TRG',
                   'ETL_YD_IMM_TRG', 'ETL_YYD_IMM_TRG');
        IF v_managed_objects <> 0 THEN
            RAISE_APPLICATION_ERROR(
                -20070,
                'Managed ETL_ object collision or incomplete installation; DBA review is required');
        END IF;
        DBMS_OUTPUT.PUT_LINE('Preflight: new installation');
    ELSE
        BEGIN
            EXECUTE IMMEDIATE
                'SELECT COUNT(*) FROM ETL_KURULUM_SURUMU ' ||
                'WHERE COMPONENT_CODE = :1 AND SCHEMA_VERSION = :2 AND CONTRACT_HASH = :3'
                INTO v_matching_markers USING c_component, c_version, c_hash;
        EXCEPTION
            WHEN OTHERS THEN
                RAISE_APPLICATION_ERROR(
                    -20071,
                    'Version marker shape is incompatible; automatic upgrade is forbidden');
        END;

        IF v_matching_markers <> 1 THEN
            RAISE_APPLICATION_ERROR(
                -20072,
                'Different or ambiguous ledger contract version; explicit migration is required');
        END IF;

        SELECT COUNT(*) INTO v_required_tables
          FROM USER_TABLES
         WHERE TABLE_NAME IN (
                   'ETL_KURULUM_SURUMU', 'ETL_YUKLEME_KILIDI',
                   'ETL_YUKLEME_DEFTERI', 'ETL_YAYIN_DEFTERI');
        IF v_required_tables <> 4 THEN
            RAISE_APPLICATION_ERROR(
                -20073,
                'Version marker exists but a managed table is missing; repair requires DBA review');
        END IF;

        SELECT COUNT(*) INTO v_valid_package_objects
          FROM USER_OBJECTS
         WHERE OBJECT_NAME = 'ETL_KANIT_PKG'
           AND OBJECT_TYPE IN ('PACKAGE', 'PACKAGE BODY')
           AND STATUS = 'VALID';
        IF v_valid_package_objects <> 2 THEN
            RAISE_APPLICATION_ERROR(
                -20074,
                'Installed package is missing or invalid; automatic repair is forbidden');
        END IF;

        SELECT COUNT(*) INTO v_package_api
          FROM USER_PROCEDURES
         WHERE OBJECT_NAME = 'ETL_KANIT_PKG'
           AND PROCEDURE_NAME IN (
               'ACQUIRE_FENCE', 'LOCK_FENCE', 'READ_FENCE',
               'PREPARE_BATCH', 'RECORD_BATCH', 'VERIFY_BATCH',
               'PREPARE_PUBLISH', 'RECORD_PUBLISH', 'VERIFY_PUBLISH');
        IF v_package_api <> 9 THEN
            RAISE_APPLICATION_ERROR(
                -20075,
                'Installed package API differs from the marked contract');
        END IF;

        SELECT COUNT(*) INTO v_package_authid
          FROM USER_PROCEDURES
         WHERE OBJECT_NAME = 'ETL_KANIT_PKG'
           AND PROCEDURE_NAME IS NULL
           AND AUTHID = 'DEFINER';
        IF v_package_authid <> 1 THEN
            RAISE_APPLICATION_ERROR(
                -20077,
                'Installed package is not the required definer-rights boundary');
        END IF;

        SELECT COUNT(*) INTO v_enabled_triggers
          FROM USER_TRIGGERS
         WHERE (
                   (TRIGGER_NAME = 'ETL_KS_IMM_TRG' AND
                    TABLE_NAME = 'ETL_KURULUM_SURUMU') OR
                   (TRIGGER_NAME = 'ETL_YD_IMM_TRG' AND
                    TABLE_NAME = 'ETL_YUKLEME_DEFTERI') OR
                   (TRIGGER_NAME = 'ETL_YYD_IMM_TRG' AND
                    TABLE_NAME = 'ETL_YAYIN_DEFTERI'))
           AND TRIGGER_TYPE = 'BEFORE STATEMENT'
           AND TRIGGERING_EVENT = 'UPDATE OR DELETE'
           AND STATUS = 'ENABLED';
        SELECT COUNT(*) INTO v_valid_trigger_objects
          FROM USER_OBJECTS
         WHERE OBJECT_NAME IN (
                   'ETL_KS_IMM_TRG', 'ETL_YD_IMM_TRG', 'ETL_YYD_IMM_TRG')
           AND OBJECT_TYPE = 'TRIGGER'
           AND STATUS = 'VALID';
        IF v_enabled_triggers <> 3 OR v_valid_trigger_objects <> 3 THEN
            RAISE_APPLICATION_ERROR(
                -20076,
                'Installed append-only guard is missing, disabled or invalid');
        END IF;

        DBMS_OUTPUT.PUT_LINE('Preflight: exact installed contract may be reapplied');
    END IF;
END;
/
