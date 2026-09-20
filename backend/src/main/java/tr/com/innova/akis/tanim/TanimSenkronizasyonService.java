package tr.com.innova.akis.tanim;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import tr.com.innova.akis.discovery.JdbcSchemaSnapshotStore;
import tr.com.innova.akis.discovery.SchemaSnapshotModels.ColumnRow;
import tr.com.innova.akis.discovery.SchemaSnapshotModels.ConstraintRow;
import tr.com.innova.akis.discovery.SchemaSnapshotModels.ProjectRef;
import tr.com.innova.akis.discovery.SchemaSnapshotModels.SnapshotRow;
import tr.com.innova.akis.metadata.ApiException;
import tr.com.innova.akis.tanim.TanimModels.TabloTanimiRow;

/**
 * Projects an existing discovery snapshot (akis.sema_goruntusu / kolon_goruntusu /
 * kisit_goruntusu — captured by the {@code discovery}/{@code oracle} packages against
 * a live Oracle/Postgres connection) into the schema metadata dictionary
 * (akis.tablo_tanimlari / kolon_tanimlari / kisit_tanimlari / kisit_kolon_tanimlari /
 * iliski_tanimlari).
 *
 * <p>This deliberately does not touch the snapshot tables or the discovery pipeline —
 * it only reads a snapshot that some other flow already captured and upserts a
 * documentation-oriented projection of it. Re-running for the same table updates the
 * existing rows in place (upsert on the real name), it never duplicates them.
 *
 * <p>FK target resolution reads the exact JSON shape {@code OracleSchemaSnapshotCodecV1}
 * writes into {@code ConstraintInput.details()} for an FK constraint:
 * {@code {"deferrability", "deleteRule", "referencedOwner", "referencedTable",
 * "referencedColumns": [...]}}. It only resolves the target when the referenced table
 * has already been synced into the SAME target schema ({@code semaAdi}) — cross-schema
 * FKs, or FKs whose target table hasn't been synced yet, are recorded as a
 * {@code kisit_tanimlari} row (tur=FOREIGN_KEY) but left without an
 * {@code iliski_tanimlari} row; syncing the target table (or the referencing table
 * again afterwards) resolves them on a later run.
 *
 * <p>Known scope limits (see plan Faz 2, Bileşen 2):
 * <ul>
 *   <li>Indexes and sequences are not synced from discovery — the discovery pipeline
 *       does not currently capture them at all (a separate, later extension).</li>
 *   <li>Identifiers are lower-cased before storing, since this dictionary's {@code ad}
 *       columns are constrained to {@code ^[a-z][a-z0-9_]{0,99}$}. Oracle sources are
 *       typically upper-case, so the stored {@code ad} is a normalized documentation
 *       projection, not necessarily the byte-for-byte case-sensitive source identifier.</li>
 * </ul>
 */
@Service
public class TanimSenkronizasyonService {

    private final TanimRepository repository;
    private final JdbcSchemaSnapshotStore snapshotStore;

    public TanimSenkronizasyonService(TanimRepository repository, JdbcSchemaSnapshotStore snapshotStore) {
        this.repository = repository;
        this.snapshotStore = snapshotStore;
    }

    /**
     * Syncs the given (or, if {@code snapshotUuid} is null, the most recently
     * discovered) snapshot of a data object into the dictionary under
     * {@code semaAdi}/{@code tabloAdi}.
     */
    @Transactional
    TabloTanimiRow senkronizeEt(
            UUID projectUuid, UUID dataObjectUuid, UUID snapshotUuid, String semaAdi, String tabloAdi) {
        ProjectRef project = snapshotStore.findProject(projectUuid)
                .orElseThrow(() -> notFound("Proje bulunamadı."));
        SnapshotRow snapshot = (snapshotUuid != null
                ? snapshotStore.find(project.id(), snapshotUuid)
                : snapshotStore.list(project.id(), dataObjectUuid).stream().findFirst())
                .orElseThrow(() -> notFound("Şema keşif kaydı (snapshot) bulunamadı. Önce discovery ile bir keşif çalıştırın."));

        long semaId = repository.upsertSema(normalize(semaAdi), null);
        long tabloId = repository.upsertTablo(semaId, normalize(tabloAdi), null);

        Map<String, Long> kolonIdByReference = new HashMap<>();
        for (ColumnRow column : snapshot.columns()) {
            long kolonId = repository.upsertKolon(
                    tabloId, column.ordinal(), normalize(column.name()), column.canonicalType(),
                    column.length(), !column.nullable(), column.defaultExpression());
            kolonIdByReference.put(column.reference(), kolonId);
        }

        for (ConstraintRow constraint : snapshot.constraints()) {
            String tur = tanimTuru(constraint.type());
            String checkIfadesi = "CHECK".equals(constraint.type()) ? checkExpression(constraint.details()) : null;
            long kisitId = repository.upsertKisit(tabloId, normalize(constraint.name()), tur, checkIfadesi);

            List<String> columnReferences = constraint.columnReferences();
            for (int index = 0; index < columnReferences.size(); index++) {
                Long kolonId = kolonIdByReference.get(columnReferences.get(index));
                if (kolonId != null) {
                    repository.upsertKisitKolon(kisitId, kolonId, index + 1);
                }
            }

            if ("FK".equals(constraint.type())) {
                resolveForeignKeyTarget(semaId, kisitId, constraint.details());
            }
        }

        return findSyncedTablo(semaId, tabloId);
    }

    /** Best-effort: wires kisitId (an already-inserted FK kisit_tanimlari row) to its target, if resolvable. */
    private void resolveForeignKeyTarget(long semaId, long kisitId, JsonNode details) {
        if (details == null) return;
        String referencedTable = details.path("referencedTable").asText(null);
        String deleteRule = details.path("deleteRule").asText("RESTRICT");
        if (referencedTable == null || referencedTable.isBlank()) return;

        List<String> referencedColumns = details.path("referencedColumns").valueStream()
                .map(node -> normalize(node.stringValue()))
                .toList();
        if (referencedColumns.isEmpty()) return;

        String normalizedTargetTable = normalize(referencedTable);
        TabloTanimiRow hedefTablo = repository.listTablolar(semaId).stream()
                .filter(row -> row.ad().equals(normalizedTargetTable))
                .findFirst()
                .orElse(null);
        if (hedefTablo == null) return; // target not synced into this schema yet — resolved on a later run.

        Long hedefKisitId = repository.listKisitlar(hedefTablo.id()).stream()
                .filter(row -> "PRIMARY_KEY".equals(row.tur()) || "UNIQUE".equals(row.tur()))
                .filter(row -> repository.listKisitKolonAdlari(row.id()).equals(referencedColumns))
                .map(row -> row.id())
                .findFirst()
                .orElse(null);
        if (hedefKisitId == null) return; // no matching PK/UNIQUE on the target for these columns yet.

        repository.upsertIliski(kisitId, hedefTablo.id(), hedefKisitId, deleteRule, "RESTRICT");
    }

    private TabloTanimiRow findSyncedTablo(long semaId, long tabloId) {
        return repository.listTablolar(semaId).stream()
                .filter(row -> row.id() == tabloId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Senkronize edilen tablo tanımı bulunamadı."));
    }

    private String tanimTuru(String discoveryType) {
        return switch (discoveryType) {
            case "PK" -> "PRIMARY_KEY";
            case "UK" -> "UNIQUE";
            case "FK" -> "FOREIGN_KEY";
            case "CHECK" -> "CHECK";
            default -> throw new IllegalStateException("Bilinmeyen kısıt türü: " + discoveryType);
        };
    }

    /** Best-effort CHECK expression extraction; falls back to a placeholder rather than failing the whole sync. */
    private String checkExpression(JsonNode details) {
        if (details == null) return "(bkz. kaynak sistem)";
        JsonNode expression = details.path("expression");
        String value = expression.isMissingNode() || !expression.isString() ? null : expression.stringValue();
        return value == null || value.isBlank() ? "(bkz. kaynak sistem)" : value;
    }

    private String normalize(String identifier) {
        return identifier.trim().toLowerCase(Locale.ROOT);
    }

    private ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }
}
