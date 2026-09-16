SET search_path TO akis, public;

ALTER TABLE fiziksel_sema ADD CONSTRAINT uq_km_work_area_scope UNIQUE(proje_id,id);

-- Explicit opt-in for DBA-managed work schemas. This migration grants no Oracle privileges.
CREATE TABLE km_work_area_policy (
    proje_id BIGINT NOT NULL,
    fiziksel_sema_id BIGINT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    allow_same_schema BOOLEAN NOT NULL DEFAULT FALSE,
    max_objects INTEGER NOT NULL CHECK (max_objects BETWEEN 1 AND 1000),
    max_rows_per_run BIGINT NOT NULL CHECK (max_rows_per_run BETWEEN 1 AND 100000000),
    max_bytes_per_run BIGINT NOT NULL CHECK (max_bytes_per_run BETWEEN 1 AND 1099511627776),
    retention_hours INTEGER NOT NULL CHECK (retention_hours BETWEEN 1 AND 8760),
    version BIGINT NOT NULL DEFAULT 1 CHECK (version>0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(proje_id,fiziksel_sema_id),
    FOREIGN KEY(proje_id,fiziksel_sema_id) REFERENCES fiziksel_sema(proje_id,id)
);
