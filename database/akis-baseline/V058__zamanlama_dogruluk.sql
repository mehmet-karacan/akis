SET search_path TO akis, public;

-- V058: Schedule correctness hardening (P1).
--   * Soft archive preserves run history; DELETE sets arsivlenme_zamani instead of removing the row.
--   * Stored timezone defaults to Europe/Istanbul when not supplied, matching the deployment region.
--   * Track the configured publication snapshot so overlap/misfire and "no silent fallback" rules are enforceable.
--   * Fire window prevents accidental bursts: the due scanner only sees times within a bounded look-ahead.

ALTER TABLE zamanlama
    ADD COLUMN IF NOT EXISTS arsivlenme_zamani TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS yayin_gorunum_kodu VARCHAR(20) NOT NULL DEFAULT 'LATEST_ACTIVE',
    ALTER COLUMN zaman_dilimi SET DEFAULT 'Europe/Istanbul';

-- Existing rows created under the old UTC default are left as-is; only future inserts without a zone
-- will pick up Europe/Istanbul. This is intentional: changing stored values would silently shift
-- already-configured schedules. Existing active schedules continue to fire in the zone they were saved with.

-- Pin validation: publication snapshot policies for a schedule are either pinned to a specific publication
-- or resolved to the latest active publication of the same scenario/environment at fire time.
-- (The application still checks the resolved publication is active; PINNED without that publication fails noisily.)
ALTER TABLE zamanlama
    ADD CONSTRAINT ck_zamanlama_yayin_gorunum CHECK (yayin_gorunum_kodu IN ('LATEST_ACTIVE', 'PINNED'));

-- The fire window is enforced by the due scanner query (see ScheduleDueScanner). Keep an index that
-- supports the bounded lookup and naturally skips archived schedules.
DROP INDEX IF EXISTS ix_zamanlama_tetikleme;
CREATE INDEX ix_zamanlama_tetikleme ON zamanlama(durum_kodu, sonraki_tetikleme_zamani)
    WHERE arsivlenme_zamani IS NULL;

-- Archived schedules are still visible in run history via is_talebi.zamanlama_id, but the UI list filters them out.
CREATE INDEX ix_zamanlama_arsiv ON zamanlama(proje_id, arsivlenme_zamani);
