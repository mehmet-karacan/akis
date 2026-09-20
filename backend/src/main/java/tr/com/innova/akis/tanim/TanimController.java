package tr.com.innova.akis.tanim;

import java.util.List;
import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tr.com.innova.akis.security.AuthorizationService;
import tr.com.innova.akis.tanim.TanimModels.IliskiTanimiRow;
import tr.com.innova.akis.tanim.TanimModels.IndeksTanimiRow;
import tr.com.innova.akis.tanim.TanimModels.KisitTanimiRow;
import tr.com.innova.akis.tanim.TanimModels.KolonTanimiRow;
import tr.com.innova.akis.tanim.TanimModels.SemaTanimiRow;
import tr.com.innova.akis.tanim.TanimModels.SiraTanimiRow;
import tr.com.innova.akis.tanim.TanimModels.TabloTanimiRow;
import static tr.com.innova.akis.security.PermissionCodes.SCHEMA_METADATA_READ;
import static tr.com.innova.akis.security.PermissionCodes.SCHEMA_METADATA_WRITE;

/**
 * API for the global schema metadata dictionary (akis.sema_tanimlari and
 * family — see database/akis-baseline/V030). Reads are system-scoped, not
 * project-scoped: the described tables belong to the running akış instance
 * itself, not to any single project. The sync endpoint takes a projectUuid
 * because the discovery snapshot it projects from is project-scoped.
 */
@RestController
@RequestMapping("/api/v1/sema-metadata")
final class TanimController {

    private final TanimService service;
    private final TanimSenkronizasyonService senkronizasyonService;
    private final AuthorizationService authorization;

    TanimController(
            TanimService service,
            TanimSenkronizasyonService senkronizasyonService,
            AuthorizationService authorization) {
        this.service = service;
        this.senkronizasyonService = senkronizasyonService;
        this.authorization = authorization;
    }

    @PostMapping("/senkronizasyon")
    TabloTanimiView senkronizeEt(@Valid @RequestBody SenkronizasyonRequest request) {
        authorization.requireSystemPermission(SCHEMA_METADATA_WRITE);
        return TabloTanimiView.from(senkronizasyonService.senkronizeEt(
                request.projectUuid(), request.dataObjectUuid(), request.snapshotUuid(),
                request.semaAdi(), request.tabloAdi()));
    }

    record SenkronizasyonRequest(
            @NotNull UUID projectUuid,
            @NotNull UUID dataObjectUuid,
            UUID snapshotUuid,
            @NotBlank String semaAdi,
            @NotBlank String tabloAdi) {
    }

    @GetMapping("/semalar")
    List<SemaTanimiView> semalar() {
        authorization.requireSystemPermission(SCHEMA_METADATA_READ);
        return service.listSemalar().stream().map(SemaTanimiView::from).toList();
    }

    @GetMapping("/semalar/{semaUuid}/tablolar")
    List<TabloTanimiView> tablolar(@PathVariable UUID semaUuid) {
        authorization.requireSystemPermission(SCHEMA_METADATA_READ);
        return service.listTablolar(semaUuid).stream().map(TabloTanimiView::from).toList();
    }

    @GetMapping("/semalar/{semaUuid}/sequences")
    List<SiraTanimiView> siralar(@PathVariable UUID semaUuid) {
        authorization.requireSystemPermission(SCHEMA_METADATA_READ);
        return service.listSiralar(semaUuid).stream().map(SiraTanimiView::from).toList();
    }

    @GetMapping("/tablolar/{tabloUuid}/kolonlar")
    List<KolonTanimiView> kolonlar(@PathVariable UUID tabloUuid) {
        authorization.requireSystemPermission(SCHEMA_METADATA_READ);
        return service.listKolonlar(tabloUuid).stream().map(KolonTanimiView::from).toList();
    }

    @GetMapping("/tablolar/{tabloUuid}/kisitlar")
    List<KisitTanimiView> kisitlar(@PathVariable UUID tabloUuid) {
        authorization.requireSystemPermission(SCHEMA_METADATA_READ);
        return service.listKisitlar(tabloUuid).stream().map(KisitTanimiView::from).toList();
    }

    @GetMapping("/tablolar/{tabloUuid}/indeksler")
    List<IndeksTanimiView> indeksler(@PathVariable UUID tabloUuid) {
        authorization.requireSystemPermission(SCHEMA_METADATA_READ);
        return service.listIndeksler(tabloUuid).stream().map(IndeksTanimiView::from).toList();
    }

    @GetMapping("/tablolar/{tabloUuid}/iliskiler")
    List<IliskiTanimiView> iliskiler(@PathVariable UUID tabloUuid) {
        authorization.requireSystemPermission(SCHEMA_METADATA_READ);
        return service.listIliskiler(tabloUuid).stream().map(IliskiTanimiView::from).toList();
    }

    record SemaTanimiView(UUID uuid, String ad, String aciklama) {
        static SemaTanimiView from(SemaTanimiRow row) {
            return new SemaTanimiView(row.uuid(), row.ad(), row.aciklama());
        }
    }

    record TabloTanimiView(UUID uuid, UUID semaTanimiUuid, String ad, String aciklama,
                           String createdBy, OffsetDateTime createdAt, String updatedBy, OffsetDateTime updatedAt) {
        static TabloTanimiView from(TabloTanimiRow row) {
            return new TabloTanimiView(row.uuid(), row.semaTanimiUuid(), row.ad(), row.aciklama(),
                    row.createdBy(), row.createdAt(), row.updatedBy(), row.updatedAt());
        }
    }

    record KolonTanimiView(
            UUID uuid, UUID tabloTanimiUuid, int siraNo, String ad, String aciklama,
            String veriTipi, Long uzunluk, boolean zorunluMu, String varsayilanDeger) {
        static KolonTanimiView from(KolonTanimiRow row) {
            return new KolonTanimiView(
                    row.uuid(), row.tabloTanimiUuid(), row.siraNo(), row.ad(), row.aciklama(),
                    row.veriTipi(), row.uzunluk(), row.zorunluMu(), row.varsayilanDeger());
        }
    }

    record KisitTanimiView(
            UUID uuid, UUID tabloTanimiUuid, String ad, String aciklama, String tur, String checkIfadesi) {
        static KisitTanimiView from(KisitTanimiRow row) {
            return new KisitTanimiView(
                    row.uuid(), row.tabloTanimiUuid(), row.ad(), row.aciklama(), row.tur(), row.checkIfadesi());
        }
    }

    record IndeksTanimiView(
            UUID uuid, UUID tabloTanimiUuid, String ad, String aciklama, String tur, boolean benzersizMi) {
        static IndeksTanimiView from(IndeksTanimiRow row) {
            return new IndeksTanimiView(
                    row.uuid(), row.tabloTanimiUuid(), row.ad(), row.aciklama(), row.tur(), row.benzersizMi());
        }
    }

    record IliskiTanimiView(
            UUID uuid, String kaynakKisitAdi, UUID hedefTabloTanimiUuid, String hedefTabloAdi,
            String hedefKisitAdi, String silmeKurali, String guncellemeKurali) {
        static IliskiTanimiView from(IliskiTanimiRow row) {
            return new IliskiTanimiView(
                    row.uuid(), row.kaynakKisitAdi(), row.hedefTabloTanimiUuid(), row.hedefTabloAdi(),
                    row.hedefKisitAdi(), row.silmeKurali(), row.guncellemeKurali());
        }
    }

    record SiraTanimiView(
            UUID uuid, UUID semaTanimiUuid, String ad, String aciklama,
            long baslangicDegeri, long artisMiktari, Long minDeger, Long maxDeger, boolean donguselMi) {
        static SiraTanimiView from(SiraTanimiRow row) {
            return new SiraTanimiView(
                    row.uuid(), row.semaTanimiUuid(), row.ad(), row.aciklama(),
                    row.baslangicDegeri(), row.artisMiktari(), row.minDeger(), row.maxDeger(), row.donguselMi());
        }
    }
}
