SET search_path TO akis, public;
CREATE TABLE km_step_journal (
    proje_id BIGINT NOT NULL, calistirma_id BIGINT NOT NULL,
    generation BIGINT NOT NULL CHECK(generation>0), worker_reference VARCHAR(200) NOT NULL,
    ordinal INTEGER NOT NULL CHECK(ordinal BETWEEN 1 AND 300), step_code VARCHAR(64) NOT NULL,
    operation VARCHAR(30) NOT NULL, site VARCHAR(10) NOT NULL, slot VARCHAR(64) NOT NULL,
    runtime_plan_hash CHAR(64) NOT NULL CHECK(runtime_plan_hash ~ '^[0-9a-f]{64}$'),
    state VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK(state IN('PENDING','RUNNING','SUCCEEDED','FAILED','UNKNOWN')),
    affected_rows BIGINT CHECK(affected_rows>=0), error_code VARCHAR(80),
    started_at TIMESTAMPTZ, completed_at TIMESTAMPTZ,
    PRIMARY KEY(calistirma_id,generation,ordinal),
    FOREIGN KEY(proje_id,calistirma_id) REFERENCES calistirma(proje_id,id)
);
