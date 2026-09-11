-- AKIS target-local Oracle 19c ledger/fencing installation.
-- Run from SQL*Plus while connected as the target object owner.
-- No connection endpoint or credential belongs in this file.

WHENEVER OSERROR EXIT FAILURE
WHENEVER SQLERROR EXIT SQL.SQLCODE ROLLBACK
SET DEFINE OFF
SET SERVEROUTPUT ON
SET VERIFY OFF

PROMPT AKIS Oracle ledger preflight
@@00_preflight.sql

PROMPT Creating target-local control tables
@@01_tables.sql

PROMPT Creating definer-rights transaction package
@@02_package.sql

PROMPT Creating append-only guards and version marker
@@03_guards_and_marker.sql

PROMPT Validating installed contract
@@validate.sql

PROMPT AKIS Oracle ledger installation completed

