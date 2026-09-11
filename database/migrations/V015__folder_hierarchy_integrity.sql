-- Keep the live Design Explorer hierarchy compatible with bundle depth limits and
-- protect direct SQL/import paths against cycles. Application validation provides
-- stable problem codes; this trigger is the final transactional invariant.

CREATE OR REPLACE FUNCTION entegrasyon.guard_folder_hierarchy()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    ancestor_depth integer := 0;
    descendant_depth integer := 1;
    creates_cycle boolean := false;
BEGIN
    -- Serializes hierarchy changes within one project, including concurrent
    -- inverse moves that would otherwise each pass an application-side check.
    PERFORM 1
      FROM entegrasyon.proje
     WHERE id = NEW.proje_id
     FOR UPDATE;

    IF NEW.ust_klasor_id IS NOT NULL THEN
        WITH RECURSIVE ancestors AS (
            SELECT k.id,
                   k.ust_klasor_id,
                   1 AS depth,
                   ARRAY[k.id]::bigint[] AS path,
                   false AS cycle
              FROM entegrasyon.klasor k
             WHERE k.proje_id = NEW.proje_id
               AND k.id = NEW.ust_klasor_id
            UNION ALL
            SELECT parent.id,
                   parent.ust_klasor_id,
                   child.depth + 1,
                   child.path || parent.id,
                   parent.id = ANY(child.path)
              FROM ancestors child
              JOIN entegrasyon.klasor parent
                ON parent.proje_id = NEW.proje_id
               AND parent.id = child.ust_klasor_id
             WHERE NOT child.cycle
               AND child.depth <= 100
        )
        SELECT COALESCE(max(depth), 0),
               COALESCE(bool_or(id = NEW.id OR cycle), false)
          INTO ancestor_depth, creates_cycle
          FROM ancestors;

        IF creates_cycle THEN
            RAISE EXCEPTION USING
                ERRCODE = '23514',
                MESSAGE = 'FOLDER_CYCLE: folder cannot be placed below its own subtree';
        END IF;
    END IF;

    IF TG_OP = 'UPDATE' THEN
        WITH RECURSIVE descendants AS (
            SELECT NEW.id AS id,
                   1 AS depth,
                   ARRAY[NEW.id]::bigint[] AS path,
                   false AS cycle
            UNION ALL
            SELECT child.id,
                   parent.depth + 1,
                   parent.path || child.id,
                   child.id = ANY(parent.path)
              FROM descendants parent
              JOIN entegrasyon.klasor child
                ON child.proje_id = NEW.proje_id
               AND child.ust_klasor_id = parent.id
             WHERE NOT parent.cycle
               AND parent.depth <= 100
        )
        SELECT COALESCE(max(depth), 1), COALESCE(bool_or(cycle), false)
          INTO descendant_depth, creates_cycle
          FROM descendants;

        IF creates_cycle THEN
            RAISE EXCEPTION USING
                ERRCODE = '23514',
                MESSAGE = 'FOLDER_CYCLE: stored folder subtree contains a cycle';
        END IF;
    END IF;

    IF ancestor_depth + descendant_depth > 100 THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'FOLDER_DEPTH_EXCEEDED: folder hierarchy cannot exceed 100 levels';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_klasor_hierarchy_integrity
BEFORE INSERT OR UPDATE OF ust_klasor_id, proje_id
ON entegrasyon.klasor
FOR EACH ROW
EXECUTE FUNCTION entegrasyon.guard_folder_hierarchy();

CREATE INDEX ix_tanim_proje_klasor
    ON entegrasyon.tanim(proje_id, klasor_id, durum_kodu, tur_kodu, kod);

