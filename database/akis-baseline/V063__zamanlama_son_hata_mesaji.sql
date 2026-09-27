SET search_path TO akis, public;

-- Explicit, durable reason a schedule was auto-suspended (e.g. its PINNED
-- publication is no longer active/approved). The fire poller sets this and
-- flips durum_kodu to ASKIDA instead of silently retrying an invalid pinned
-- target or falling back to some other publication. Cleared only by an
-- explicit resume or an edit back to AKTIF, so it never lingers stale.
ALTER TABLE zamanlama ADD COLUMN son_hata_mesaji VARCHAR(500);
