SET search_path TO akis, public;

-- Per-physical-schema policy for AKIS-generated target DDL (Faz A: PostgreSQL target provisioning from a pinned Oracle
-- snapshot). Mirrors km_work_area_policy: closed by default, an admin opts a schema in explicitly.
--   DDL_DISABLED      — AKIS may not generate or run DDL against this schema (default).
--   DDL_GENERATE_ONLY — AKIS returns CREATE SCHEMA/CREATE TABLE text for a DBA to review and run; never executes it.
--   DDL_AUTO_CREATE   — AKIS may execute the DDL itself (DEV/TEST use; keep off production schemas).
CREATE TABLE hedef_saglama_politikasi (
    proje_id BIGINT NOT NULL,
    fiziksel_sema_id BIGINT NOT NULL,
    politika_kodu VARCHAR(20) NOT NULL DEFAULT 'DDL_DISABLED'
        CHECK (politika_kodu IN ('DDL_DISABLED', 'DDL_GENERATE_ONLY', 'DDL_AUTO_CREATE')),
    versiyon_no BIGINT NOT NULL DEFAULT 1 CHECK (versiyon_no > 0),
    guncellenme_zamani TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (proje_id, fiziksel_sema_id),
    FOREIGN KEY (fiziksel_sema_id) REFERENCES fiziksel_sema(id)
);
