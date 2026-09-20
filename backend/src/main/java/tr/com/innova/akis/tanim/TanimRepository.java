package tr.com.innova.akis.tanim;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import tr.com.innova.akis.tanim.TanimModels.IliskiTanimiRow;
import tr.com.innova.akis.tanim.TanimModels.IndeksTanimiRow;
import tr.com.innova.akis.tanim.TanimModels.KisitTanimiRow;
import tr.com.innova.akis.tanim.TanimModels.KolonTanimiRow;
import tr.com.innova.akis.tanim.TanimModels.SemaTanimiRow;
import tr.com.innova.akis.tanim.TanimModels.SiraTanimiRow;
import tr.com.innova.akis.tanim.TanimModels.TabloTanimiRow;

/**
 * Read-only access to the global (project-independent) schema metadata
 * dictionary: akis.sema_tanimlari / tablo_tanimlari / kolon_tanimlari /
 * kisit_tanimlari / iliski_tanimlari / indeks_tanimlari / sira_tanimlari.
 * See database/akis-baseline/V030__sema_metadata_tanimlari.sql.
 */
@Repository
class TanimRepository {

    private final JdbcClient jdbc;

    TanimRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<SemaTanimiRow> listSemalar() {
        return jdbc.sql("select id, uuid, ad, aciklama from akis.sema_tanimlari order by ad")
                .query(this::mapSema)
                .list();
    }

    Optional<SemaTanimiRow> findSemaByUuid(UUID uuid) {
        return jdbc.sql("select id, uuid, ad, aciklama from akis.sema_tanimlari where uuid = :uuid")
                .param("uuid", uuid)
                .query(this::mapSema)
                .optional();
    }

    List<TabloTanimiRow> listTablolar(long semaTanimiId) {
        return jdbc.sql(tabloSelect() + " where t.sema_tanimi_id = :semaTanimiId order by t.ad")
                .param("semaTanimiId", semaTanimiId)
                .query(this::mapTablo)
                .list();
    }

    Optional<TabloTanimiRow> findTabloByUuid(UUID uuid) {
        return jdbc.sql(tabloSelect() + " where t.uuid = :uuid")
                .param("uuid", uuid)
                .query(this::mapTablo)
                .optional();
    }

    List<KolonTanimiRow> listKolonlar(long tabloTanimiId) {
        return jdbc.sql(kolonSelect() + " where k.tablo_tanimi_id = :tabloTanimiId order by k.sira_no")
                .param("tabloTanimiId", tabloTanimiId)
                .query(this::mapKolon)
                .list();
    }

    List<KisitTanimiRow> listKisitlar(long tabloTanimiId) {
        return jdbc.sql(kisitSelect() + " where k.tablo_tanimi_id = :tabloTanimiId order by k.ad")
                .param("tabloTanimiId", tabloTanimiId)
                .query(this::mapKisit)
                .list();
    }

    List<IndeksTanimiRow> listIndeksler(long tabloTanimiId) {
        return jdbc.sql(indeksSelect() + " where i.tablo_tanimi_id = :tabloTanimiId order by i.ad")
                .param("tabloTanimiId", tabloTanimiId)
                .query(this::mapIndeks)
                .list();
    }

    List<SiraTanimiRow> listSiralar(long semaTanimiId) {
        return jdbc.sql(siraSelect() + " where s.sema_tanimi_id = :semaTanimiId order by s.ad")
                .param("semaTanimiId", semaTanimiId)
                .query(this::mapSira)
                .list();
    }

    List<IliskiTanimiRow> listIliskiler(long tabloTanimiId) {
        return jdbc.sql(iliskiSelect() + " where kk.tablo_tanimi_id = :tabloTanimiId order by hedef_t.ad")
                .param("tabloTanimiId", tabloTanimiId)
                .query(this::mapIliski)
                .list();
    }

    // --- Upserts used by TanimSenkronizasyonService to project a discovery snapshot
    // (akis.sema_goruntusu/kolon_goruntusu/kisit_goruntusu) into this dictionary. ---

    long upsertSema(String ad, String aciklama) {
        return jdbc.sql("""
                        insert into akis.sema_tanimlari(ad, aciklama)
                        values (:ad, :aciklama)
                        on conflict (ad) do update set aciklama = coalesce(akis.sema_tanimlari.aciklama, excluded.aciklama)
                        returning id
                        """)
                .param("ad", ad)
                .param("aciklama", aciklama, Types.VARCHAR)
                .query(Long.class)
                .single();
    }

    long upsertTablo(long semaTanimiId, String ad, String aciklama) {
        return jdbc.sql("""
                        insert into akis.tablo_tanimlari(sema_tanimi_id, ad, aciklama)
                        values (:semaTanimiId, :ad, :aciklama)
                        on conflict (sema_tanimi_id, ad)
                            do update set aciklama = coalesce(akis.tablo_tanimlari.aciklama, excluded.aciklama)
                        returning id
                        """)
                .param("semaTanimiId", semaTanimiId)
                .param("ad", ad)
                .param("aciklama", aciklama, Types.VARCHAR)
                .query(Long.class)
                .single();
    }

    long upsertKolon(
            long tabloTanimiId, int siraNo, String ad, String veriTipi,
            Long uzunluk, boolean zorunluMu, String varsayilanDeger) {
        return jdbc.sql("""
                        insert into akis.kolon_tanimlari(
                            tablo_tanimi_id, sira_no, ad, veri_tipi, uzunluk, zorunlu_mu, varsayilan_deger)
                        values (:tabloTanimiId, :siraNo, :ad, :veriTipi, :uzunluk, :zorunluMu, :varsayilanDeger)
                        on conflict (tablo_tanimi_id, ad) do update set
                            sira_no = excluded.sira_no, veri_tipi = excluded.veri_tipi,
                            uzunluk = excluded.uzunluk, zorunlu_mu = excluded.zorunlu_mu,
                            varsayilan_deger = excluded.varsayilan_deger,
                            guncellenme_zamani = current_timestamp
                        returning id
                        """)
                .param("tabloTanimiId", tabloTanimiId)
                .param("siraNo", siraNo)
                .param("ad", ad)
                .param("veriTipi", veriTipi)
                .param("uzunluk", uzunluk, Types.BIGINT)
                .param("zorunluMu", zorunluMu)
                .param("varsayilanDeger", varsayilanDeger, Types.VARCHAR)
                .query(Long.class)
                .single();
    }

    long upsertKisit(long tabloTanimiId, String ad, String tur, String checkIfadesi) {
        return jdbc.sql("""
                        insert into akis.kisit_tanimlari(tablo_tanimi_id, ad, tur, check_ifadesi)
                        values (:tabloTanimiId, :ad, :tur, :checkIfadesi)
                        on conflict (tablo_tanimi_id, ad) do update set
                            tur = excluded.tur, check_ifadesi = excluded.check_ifadesi,
                            guncellenme_zamani = current_timestamp
                        returning id
                        """)
                .param("tabloTanimiId", tabloTanimiId)
                .param("ad", ad)
                .param("tur", tur)
                .param("checkIfadesi", checkIfadesi, Types.VARCHAR)
                .query(Long.class)
                .single();
    }

    void upsertKisitKolon(long kisitTanimiId, long kolonTanimiId, int siraNo) {
        jdbc.sql("""
                        insert into akis.kisit_kolon_tanimlari(kisit_tanimi_id, kolon_tanimi_id, sira_no)
                        values (:kisitTanimiId, :kolonTanimiId, :siraNo)
                        on conflict (kisit_tanimi_id, kolon_tanimi_id) do update set sira_no = excluded.sira_no
                        """)
                .param("kisitTanimiId", kisitTanimiId)
                .param("kolonTanimiId", kolonTanimiId)
                .param("siraNo", siraNo)
                .update();
    }

    /** Ordered column names covered by a constraint — used to match an FK's referenced columns to a target PK/UNIQUE. */
    List<String> listKisitKolonAdlari(long kisitTanimiId) {
        return jdbc.sql("""
                        select k.ad
                          from akis.kisit_kolon_tanimlari kk
                          join akis.kolon_tanimlari k on k.id = kk.kolon_tanimi_id
                         where kk.kisit_tanimi_id = :kisitTanimiId
                         order by kk.sira_no
                        """)
                .param("kisitTanimiId", kisitTanimiId)
                .query(String.class)
                .list();
    }

    void upsertIliski(
            long kaynakKisitTanimiId, long hedefTabloTanimiId, long hedefKisitTanimiId,
            String silmeKurali, String guncellemeKurali) {
        jdbc.sql("""
                        insert into akis.iliski_tanimlari(
                            kaynak_kisit_tanimi_id, hedef_tablo_tanimi_id, hedef_kisit_tanimi_id,
                            silme_kurali, guncelleme_kurali)
                        values (:kaynakKisitTanimiId, :hedefTabloTanimiId, :hedefKisitTanimiId,
                                :silmeKurali, :guncellemeKurali)
                        on conflict (kaynak_kisit_tanimi_id) do update set
                            hedef_tablo_tanimi_id = excluded.hedef_tablo_tanimi_id,
                            hedef_kisit_tanimi_id = excluded.hedef_kisit_tanimi_id,
                            silme_kurali = excluded.silme_kurali,
                            guncelleme_kurali = excluded.guncelleme_kurali,
                            guncellenme_zamani = current_timestamp
                        """)
                .param("kaynakKisitTanimiId", kaynakKisitTanimiId)
                .param("hedefTabloTanimiId", hedefTabloTanimiId)
                .param("hedefKisitTanimiId", hedefKisitTanimiId)
                .param("silmeKurali", silmeKurali)
                .param("guncellemeKurali", guncellemeKurali)
                .update();
    }

    private String tabloSelect() {
        return """
                select t.id, t.uuid, t.sema_tanimi_id, s.uuid as sema_tanimi_uuid, t.ad, t.aciklama,
                       creator.gorunen_ad as created_by, t.olusturulma_zamani as created_at,
                       updater.gorunen_ad as updated_by, t.guncellenme_zamani as updated_at
                  from akis.tablo_tanimlari t
                  join akis.sema_tanimlari s on s.id = t.sema_tanimi_id
                  left join akis.kullanici creator on creator.id = t.olusturan_kullanici_id
                  left join akis.kullanici updater on updater.id = t.guncelleyen_kullanici_id
                """;
    }

    private TabloTanimiRow mapTablo(ResultSet rs, int rowNum) throws SQLException {
        return new TabloTanimiRow(
                rs.getLong("id"), rs.getObject("uuid", UUID.class),
                rs.getLong("sema_tanimi_id"), rs.getObject("sema_tanimi_uuid", UUID.class),
                rs.getString("ad"), rs.getString("aciklama"),
                rs.getString("created_by"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getString("updated_by"), rs.getObject("updated_at", OffsetDateTime.class));
    }

    private SemaTanimiRow mapSema(ResultSet rs, int rowNum) throws SQLException {
        return new SemaTanimiRow(
                rs.getLong("id"), rs.getObject("uuid", UUID.class),
                rs.getString("ad"), rs.getString("aciklama"));
    }

    private String kolonSelect() {
        return """
                select k.id, k.uuid, k.tablo_tanimi_id, t.uuid as tablo_tanimi_uuid, k.sira_no,
                       k.ad, k.aciklama, k.veri_tipi, k.uzunluk, k.zorunlu_mu, k.varsayilan_deger
                  from akis.kolon_tanimlari k
                  join akis.tablo_tanimlari t on t.id = k.tablo_tanimi_id
                """;
    }

    private KolonTanimiRow mapKolon(ResultSet rs, int rowNum) throws SQLException {
        return new KolonTanimiRow(
                rs.getLong("id"), rs.getObject("uuid", UUID.class),
                rs.getLong("tablo_tanimi_id"), rs.getObject("tablo_tanimi_uuid", UUID.class),
                rs.getInt("sira_no"), rs.getString("ad"), rs.getString("aciklama"),
                rs.getString("veri_tipi"), rs.getObject("uzunluk", Long.class),
                rs.getBoolean("zorunlu_mu"), rs.getString("varsayilan_deger"));
    }

    private String kisitSelect() {
        return """
                select k.id, k.uuid, k.tablo_tanimi_id, t.uuid as tablo_tanimi_uuid,
                       k.ad, k.aciklama, k.tur, k.check_ifadesi
                  from akis.kisit_tanimlari k
                  join akis.tablo_tanimlari t on t.id = k.tablo_tanimi_id
                """;
    }

    private KisitTanimiRow mapKisit(ResultSet rs, int rowNum) throws SQLException {
        return new KisitTanimiRow(
                rs.getLong("id"), rs.getObject("uuid", UUID.class),
                rs.getLong("tablo_tanimi_id"), rs.getObject("tablo_tanimi_uuid", UUID.class),
                rs.getString("ad"), rs.getString("aciklama"), rs.getString("tur"),
                rs.getString("check_ifadesi"));
    }

    private String indeksSelect() {
        return """
                select i.id, i.uuid, i.tablo_tanimi_id, t.uuid as tablo_tanimi_uuid,
                       i.ad, i.aciklama, i.tur, i.benzersiz_mi
                  from akis.indeks_tanimlari i
                  join akis.tablo_tanimlari t on t.id = i.tablo_tanimi_id
                """;
    }

    private IndeksTanimiRow mapIndeks(ResultSet rs, int rowNum) throws SQLException {
        return new IndeksTanimiRow(
                rs.getLong("id"), rs.getObject("uuid", UUID.class),
                rs.getLong("tablo_tanimi_id"), rs.getObject("tablo_tanimi_uuid", UUID.class),
                rs.getString("ad"), rs.getString("aciklama"), rs.getString("tur"),
                rs.getBoolean("benzersiz_mi"));
    }

    private String siraSelect() {
        return """
                select s.id, s.uuid, s.sema_tanimi_id, se.uuid as sema_tanimi_uuid,
                       s.ad, s.aciklama, s.baslangic_degeri, s.artis_miktari,
                       s.min_deger, s.max_deger, s.dongusel_mi, s.sahibi_kolon_tanimi_id
                  from akis.sira_tanimlari s
                  join akis.sema_tanimlari se on se.id = s.sema_tanimi_id
                """;
    }

    private SiraTanimiRow mapSira(ResultSet rs, int rowNum) throws SQLException {
        return new SiraTanimiRow(
                rs.getLong("id"), rs.getObject("uuid", UUID.class),
                rs.getLong("sema_tanimi_id"), rs.getObject("sema_tanimi_uuid", UUID.class),
                rs.getString("ad"), rs.getString("aciklama"),
                rs.getLong("baslangic_degeri"), rs.getLong("artis_miktari"),
                rs.getObject("min_deger", Long.class), rs.getObject("max_deger", Long.class),
                rs.getBoolean("dongusel_mi"), rs.getObject("sahibi_kolon_tanimi_id", Long.class));
    }

    private String iliskiSelect() {
        return """
                select il.id, il.uuid, il.kaynak_kisit_tanimi_id, kk.ad as kaynak_kisit_adi,
                       il.hedef_tablo_tanimi_id, hedef_t.uuid as hedef_tablo_tanimi_uuid,
                       hedef_t.ad as hedef_tablo_adi,
                       il.hedef_kisit_tanimi_id, hedef_k.ad as hedef_kisit_adi,
                       il.silme_kurali, il.guncelleme_kurali
                  from akis.iliski_tanimlari il
                  join akis.kisit_tanimlari kk on kk.id = il.kaynak_kisit_tanimi_id
                  join akis.tablo_tanimlari hedef_t on hedef_t.id = il.hedef_tablo_tanimi_id
                  join akis.kisit_tanimlari hedef_k on hedef_k.id = il.hedef_kisit_tanimi_id
                """;
    }

    private IliskiTanimiRow mapIliski(ResultSet rs, int rowNum) throws SQLException {
        return new IliskiTanimiRow(
                rs.getLong("id"), rs.getObject("uuid", UUID.class),
                rs.getLong("kaynak_kisit_tanimi_id"), rs.getString("kaynak_kisit_adi"),
                rs.getLong("hedef_tablo_tanimi_id"), rs.getObject("hedef_tablo_tanimi_uuid", UUID.class),
                rs.getString("hedef_tablo_adi"),
                rs.getLong("hedef_kisit_tanimi_id"), rs.getString("hedef_kisit_adi"),
                rs.getString("silme_kurali"), rs.getString("guncelleme_kurali"));
    }
}
