SET search_path TO akis, public;

-- Target-based work table names (AKIS_C$_<HEDEF>) recur across runs by design. Name uniqueness in the registry
-- therefore applies only while the object may still exist in Oracle; dropped/cleaned rows keep their history
-- without blocking the next run of the same mapping.
ALTER TABLE km_work_object DROP CONSTRAINT km_work_object_database_identity_owner_name_object_name_key;
CREATE UNIQUE INDEX uq_km_work_object_canli_ad
    ON km_work_object(database_identity, owner_name, object_name)
    WHERE state NOT IN ('DROPPED', 'CLEANED');
