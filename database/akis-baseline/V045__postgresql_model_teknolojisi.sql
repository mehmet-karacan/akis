SET search_path TO akis, public;

-- Models may now be declared on PostgreSQL logical schemas (topology already knows the provider since V033).
-- Reverse engineering, KM runtime and publication keep rejecting POSTGRESQL bindings until their adapters exist;
-- the catalog still requires the model technology to equal the logical schema's provider.
ALTER TABLE model DROP CONSTRAINT ck_model_teknoloji;
ALTER TABLE model ADD CONSTRAINT ck_model_teknoloji CHECK (teknoloji_kodu IN ('ORACLE', 'POSTGRESQL'));
