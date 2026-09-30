create schema if not exists upstream_schema;

create table if not exists upstream_schema.sample_table (
    id numeric not null,
    tanimlayan_kullanici_id numeric(19, 0),
    tanimlama_zamani timestamp(6) without time zone,
    guncelleme_zamani timestamp(6) without time zone,
    guncelleyen_kullanici_id numeric(19, 0),
    versiyon numeric(10, 0),
    channel_id numeric not null,
    aciklama varchar(255) not null,
    sayima_dahil smallint default 1,
    gecmis_kayitlar_dahil smallint default 1 not null,
    durum numeric default 1 not null,
    yetkili_e_mail varchar(500),
    periyot numeric(3, 0) not null,
    constraint pk_sample_table primary key (id),
    constraint cc_sample_table_sayima_dahil
        check (sayima_dahil in (0, 1)),
    constraint cc_gecmise_kayitlar_hkds_dahil
        check (gecmis_kayitlar_dahil in (0, 1)),
    constraint cc_sample_table_durum
        check (durum in (0, 1)),
    constraint fk_hkd_tipi_channel_id
        foreign key (channel_id)
        references upstream_schema.channel (id)
);
