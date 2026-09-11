-- Akis Oracle 12c metadata inventory (read-only baseline)
-- Compatible target: Oracle 12.1 and 12.2, application-user privileges.
-- DBeaver: change only target_owner, then Execute SQL Script (Alt+X).
-- Use an unquoted, uppercase Oracle schema name.
@set target_owner = TTBP

-- No DDL/DML is executed. Source bodies, SQL bodies, defaults, DB link names,
-- grants and optimizer/cardinality statistics are intentionally not exported.

-- 01. Database/session identity and product version.
SELECT SYS_CONTEXT('USERENV', 'DB_UNIQUE_NAME') AS database_unique_name,
       SYS_CONTEXT('USERENV', 'DB_NAME') AS database_name,
       SYS_CONTEXT('USERENV', 'CON_NAME') AS container_name,
       SYS_CONTEXT('USERENV', 'CURRENT_USER') AS current_user,
       SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') AS current_schema,
       SYS_CONTEXT('USERENV', 'SERVICE_NAME') AS service_name
  FROM sys.dual;

SELECT product, version, status
  FROM product_component_version
 ORDER BY product;

-- 02. Object counts and object inventory.
SELECT object_type,
       COUNT(*) AS object_count,
       SUM(CASE WHEN status = 'VALID' THEN 1 ELSE 0 END) AS valid_count,
       SUM(CASE WHEN status <> 'VALID' THEN 1 ELSE 0 END) AS invalid_count
  FROM all_objects
 WHERE owner = UPPER('${target_owner}')
 GROUP BY object_type
 ORDER BY object_type;

SELECT object_name, subobject_name, object_type, status,
       created, last_ddl_time, temporary, generated
  FROM all_objects
 WHERE owner = UPPER('${target_owner}')
 ORDER BY object_type, object_name, subobject_name;

-- 03. Tables. Statistics and storage capacity values are omitted.
SELECT table_name, tablespace_name, cluster_name, iot_type,
       status, logging, partitioned, temporary, nested,
       compression, compress_for, row_movement, segment_created
  FROM all_tables
 WHERE owner = UPPER('${target_owner}')
 ORDER BY table_name;

-- 04. User-visible columns. DATA_DEFAULT is LONG and deliberately omitted.
SELECT table_name, column_id, column_name,
       data_type, data_type_mod, data_type_owner,
       data_length, data_precision, data_scale,
       char_length, char_used, nullable,
       virtual_column, identity_column
  FROM all_tab_columns
 WHERE owner = UPPER('${target_owner}')
 ORDER BY table_name, column_id;

-- 05. Business descriptions. Review comments before sharing the export.
SELECT table_name, table_type, comments
  FROM all_tab_comments
 WHERE owner = UPPER('${target_owner}')
 ORDER BY table_name;

SELECT table_name, column_name, comments
  FROM all_col_comments
 WHERE owner = UPPER('${target_owner}')
 ORDER BY table_name, column_name;

-- 06. Constraints with ordered local and referenced FK columns.
-- CHECK expressions are omitted because SEARCH_CONDITION is LONG and can
-- contain sensitive literals. C rows still identify CHECK/NOT NULL metadata.
SELECT c.table_name,
       c.constraint_name,
       c.constraint_type,
       c.status,
       c.validated,
       c.generated,
       c.deferrable,
       c.deferred,
       c.rely,
       c.delete_rule,
       cc.position,
       cc.column_name,
       c.r_owner AS referenced_owner,
       r.table_name AS referenced_table,
       rc.column_name AS referenced_column
  FROM all_constraints c
  LEFT JOIN all_cons_columns cc
    ON cc.owner = c.owner
   AND cc.constraint_name = c.constraint_name
   AND cc.table_name = c.table_name
  LEFT JOIN all_constraints r
    ON r.owner = c.r_owner
   AND r.constraint_name = c.r_constraint_name
  LEFT JOIN all_cons_columns rc
    ON rc.owner = r.owner
   AND rc.constraint_name = r.constraint_name
   AND rc.table_name = r.table_name
   AND rc.position = cc.position
 WHERE c.owner = UPPER('${target_owner}')
 ORDER BY c.table_name, c.constraint_type, c.constraint_name, cc.position;

-- 07. Indexes and ordered columns. A UNIQUE index is not automatically a UK.
SELECT index_name, index_type, table_name, uniqueness,
       compression, prefix_length, tablespace_name, status,
       partitioned, temporary, generated, visibility
  FROM all_indexes
 WHERE owner = UPPER('${target_owner}')
 ORDER BY table_name, index_name;

SELECT table_name, index_name, column_position,
       column_name, column_length, char_length, descend
  FROM all_ind_columns
 WHERE index_owner = UPPER('${target_owner}')
 ORDER BY table_name, index_name, column_position;

-- 08. Partition key definitions. Partition bounds/values are omitted.
SELECT name AS object_name, object_type, column_name, column_position
  FROM all_part_key_columns
 WHERE owner = UPPER('${target_owner}')
 ORDER BY object_type, name, column_position;

-- 09. LOB storage definitions.
SELECT table_name, column_name, segment_name, tablespace_name,
       index_name, chunk, pctversion, retention,
       cache, logging, in_row
  FROM all_lobs
 WHERE owner = UPPER('${target_owner}')
 ORDER BY table_name, column_name;

-- 10. Views and materialized views. Query text is deliberately omitted.
SELECT view_name, text_length, view_type_owner, view_type,
       superview_name
  FROM all_views
 WHERE owner = UPPER('${target_owner}')
 ORDER BY view_name;

SELECT mview_name, container_name, query_len,
       updatable, rewrite_enabled, refresh_mode,
       refresh_method, build_mode, last_refresh_type,
       last_refresh_date, staleness
  FROM all_mviews
 WHERE owner = UPPER('${target_owner}')
 ORDER BY mview_name;

-- 11. Sequences. LAST_NUMBER is not guaranteed to be the exact next value.
SELECT sequence_name, min_value, max_value, increment_by,
       cycle_flag, order_flag, cache_size, last_number
  FROM all_sequences
 WHERE sequence_owner = UPPER('${target_owner}')
 ORDER BY sequence_name;

-- 12. Packages, procedures/functions and callable signatures.
-- Source text and argument default expressions are deliberately omitted.
SELECT object_name, procedure_name, object_type,
       subprogram_id, overload, aggregate,
       pipelined, parallel, deterministic, authid
  FROM all_procedures
 WHERE owner = UPPER('${target_owner}')
 ORDER BY object_name, subprogram_id, procedure_name;

SELECT package_name, object_name, overload,
       subprogram_id, sequence, position, argument_name,
       in_out, data_level, data_type, data_length,
       data_precision, data_scale, char_length,
       type_owner, type_name, type_subname, pls_type, defaulted
  FROM all_arguments
 WHERE owner = UPPER('${target_owner}')
 ORDER BY package_name, object_name, overload, subprogram_id, sequence;

-- 13. Triggers. Trigger body, WHEN clause and descriptions are omitted.
SELECT trigger_name, trigger_type, triggering_event,
       table_owner, table_name, base_object_type,
       column_name, status, action_type
  FROM all_triggers
 WHERE owner = UPPER('${target_owner}')
 ORDER BY table_name, trigger_name;

-- 14. Synonyms. Remote database link names are masked.
SELECT synonym_name, table_owner, table_name,
       CASE WHEN db_link IS NULL THEN 'LOCAL' ELSE 'REMOTE' END AS link_scope
  FROM all_synonyms
 WHERE owner = UPPER('${target_owner}')
 ORDER BY synonym_name;

-- 15. Static dependencies. Dynamic SQL references cannot appear here.
SELECT owner, name, type,
       referenced_owner, referenced_name, referenced_type,
       dependency_type
  FROM all_dependencies
 WHERE owner = UPPER('${target_owner}')
 ORDER BY owner, name, type, referenced_owner, referenced_name;

-- 16. Invalid objects requiring attention.
SELECT object_type, object_name, subobject_name,
       status, last_ddl_time
  FROM all_objects
 WHERE owner = UPPER('${target_owner}')
   AND status <> 'VALID'
 ORDER BY object_type, object_name, subobject_name;
