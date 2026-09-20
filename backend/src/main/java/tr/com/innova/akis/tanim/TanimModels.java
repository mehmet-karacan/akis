package tr.com.innova.akis.tanim;

import java.time.OffsetDateTime;
import java.util.UUID;

final class TanimModels {

    private TanimModels() {
    }

    record SemaTanimiRow(long id, UUID uuid, String ad, String aciklama) {
    }

    record TabloTanimiRow(
            long id,
            UUID uuid,
            long semaTanimiId,
            UUID semaTanimiUuid,
            String ad,
            String aciklama,
            String createdBy,
            OffsetDateTime createdAt,
            String updatedBy,
            OffsetDateTime updatedAt) {
    }

    record KolonTanimiRow(
            long id,
            UUID uuid,
            long tabloTanimiId,
            UUID tabloTanimiUuid,
            int siraNo,
            String ad,
            String aciklama,
            String veriTipi,
            Long uzunluk,
            boolean zorunluMu,
            String varsayilanDeger) {
    }

    record KisitTanimiRow(
            long id,
            UUID uuid,
            long tabloTanimiId,
            UUID tabloTanimiUuid,
            String ad,
            String aciklama,
            String tur,
            String checkIfadesi) {
    }

    record KisitKolonTanimiRow(
            long id,
            UUID uuid,
            long kisitTanimiId,
            long kolonTanimiId,
            String kolonAdi,
            int siraNo) {
    }

    record IliskiTanimiRow(
            long id,
            UUID uuid,
            long kaynakKisitTanimiId,
            String kaynakKisitAdi,
            long hedefTabloTanimiId,
            UUID hedefTabloTanimiUuid,
            String hedefTabloAdi,
            long hedefKisitTanimiId,
            String hedefKisitAdi,
            String silmeKurali,
            String guncellemeKurali) {
    }

    record IndeksTanimiRow(
            long id,
            UUID uuid,
            long tabloTanimiId,
            UUID tabloTanimiUuid,
            String ad,
            String aciklama,
            String tur,
            boolean benzersizMi) {
    }

    record SiraTanimiRow(
            long id,
            UUID uuid,
            long semaTanimiId,
            UUID semaTanimiUuid,
            String ad,
            String aciklama,
            long baslangicDegeri,
            long artisMiktari,
            Long minDeger,
            Long maxDeger,
            boolean donguselMi,
            Long sahibiKolonTanimiId) {
    }
}
