-- V057: Job-based JSON data export infrastructure

-- Tracks asynchronous export jobs and their lifecycle.
create table if not exists akis.veri_export_isi (
    id bigserial primary key,
    uuid uuid not null unique,
    proje_id bigint not null references akis.proje(id),
    kullanici_id bigint not null references akis.kullanici(id),
    saglayici_kodu varchar(64) not null,
    kaynak_kodu varchar(64) not null,
    kapsam varchar(16) not null,
    filtre_snapshot jsonb not null default '[]'::jsonb,
    secili_kolonlar varchar(128)[] not null default array[]::varchar[],
    detayli_mi boolean not null default false,
    durum varchar(16) not null default 'QUEUED',
    islenen_satir bigint not null default 0,
    sonuc_satir bigint not null default 0,
    byte_sayisi bigint not null default 0,
    hata_kodu varchar(128),
    hata_mesaji text,
    sona_erme_zamani timestamp with time zone not null,
    olusturulma_zamani timestamp with time zone not null default current_timestamp,
    baslama_zamani timestamp with time zone,
    bitis_zamani timestamp with time zone,
    guncellenme_zamani timestamp with time zone not null default current_timestamp,
    versiyon_no bigint not null default 1,

    constraint veri_export_isi_durum_check check (durum in (
        'QUEUED','RUNNING','COMPLETED','FAILED','CANCELLED','EXPIRED'
    )),
    constraint veri_export_isi_kapsam_check check (kapsam in (
        'VISIBLE','FILTERED','ALL','SELECTED'
    ))
);

-- Stores the finalized JSON spool file path and checksum for each job.
create table if not exists akis.veri_export_ciktisi (
    id bigserial primary key,
    export_isi_id bigint not null unique references akis.veri_export_isi(id) on delete cascade,
    dosya_yolu varchar(512) not null,
    checksum varchar(128) not null,
    olusturulma_zamani timestamp with time zone not null default current_timestamp
);

-- Fast lookup of recent jobs by project and creator/status.
create index if not exists idx_veri_export_isi_proje_durum_kullanici
    on akis.veri_export_isi(proje_id, durum, kullanici_id);

-- Expiry cleanup sweeps non-terminal jobs older than retention.
create index if not exists idx_veri_export_isi_sona_erme_durum
    on akis.veri_export_isi(sona_erme_zamani, durum)
    where durum in ('QUEUED','RUNNING','COMPLETED');

-- Unique lookup by UUID (also enforced by the table unique constraint, but an explicit index aids planning).
create index if not exists idx_veri_export_isi_uuid
    on akis.veri_export_isi(uuid);
