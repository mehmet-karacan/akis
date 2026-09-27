-- Global export jobs intentionally have no project owner. Project-scoped jobs
-- continue to use proje_id and its existing foreign key.
alter table akis.veri_export_isi
    alter column proje_id drop not null;

comment on column akis.veri_export_isi.proje_id is
    'Proje kapsamlı export için dolu; sistem kapsamlı export için NULL.';

create index if not exists idx_veri_export_isi_global_creator_status
    on akis.veri_export_isi(kullanici_id, durum, olusturulma_zamani desc)
    where proje_id is null;
