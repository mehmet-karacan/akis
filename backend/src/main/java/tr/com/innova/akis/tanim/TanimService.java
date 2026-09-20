package tr.com.innova.akis.tanim;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import tr.com.innova.akis.metadata.ApiException;
import tr.com.innova.akis.tanim.TanimModels.IliskiTanimiRow;
import tr.com.innova.akis.tanim.TanimModels.IndeksTanimiRow;
import tr.com.innova.akis.tanim.TanimModels.KisitTanimiRow;
import tr.com.innova.akis.tanim.TanimModels.KolonTanimiRow;
import tr.com.innova.akis.tanim.TanimModels.SemaTanimiRow;
import tr.com.innova.akis.tanim.TanimModels.SiraTanimiRow;
import tr.com.innova.akis.tanim.TanimModels.TabloTanimiRow;

@Service
public class TanimService {

    private final TanimRepository repository;

    public TanimService(TanimRepository repository) {
        this.repository = repository;
    }

    List<SemaTanimiRow> listSemalar() {
        return repository.listSemalar();
    }

    List<TabloTanimiRow> listTablolar(UUID semaUuid) {
        return repository.listTablolar(sema(semaUuid).id());
    }

    List<KolonTanimiRow> listKolonlar(UUID tabloUuid) {
        return repository.listKolonlar(tablo(tabloUuid).id());
    }

    List<KisitTanimiRow> listKisitlar(UUID tabloUuid) {
        return repository.listKisitlar(tablo(tabloUuid).id());
    }

    List<IndeksTanimiRow> listIndeksler(UUID tabloUuid) {
        return repository.listIndeksler(tablo(tabloUuid).id());
    }

    List<IliskiTanimiRow> listIliskiler(UUID tabloUuid) {
        return repository.listIliskiler(tablo(tabloUuid).id());
    }

    List<SiraTanimiRow> listSiralar(UUID semaUuid) {
        return repository.listSiralar(sema(semaUuid).id());
    }

    private SemaTanimiRow sema(UUID uuid) {
        return repository.findSemaByUuid(uuid)
                .orElseThrow(() -> notFound("Şema tanımı bulunamadı."));
    }

    private TabloTanimiRow tablo(UUID uuid) {
        return repository.findTabloByUuid(uuid)
                .orElseThrow(() -> notFound("Tablo tanımı bulunamadı."));
    }

    private ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }
}
