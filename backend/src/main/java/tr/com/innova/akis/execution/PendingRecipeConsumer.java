package tr.com.innova.akis.execution;

import java.util.List;
import java.util.Objects;
import java.time.OffsetDateTime;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tr.com.innova.akis.metadata.ApiException;

/** Materializes imported schedule recipes only after a matching real publication exists. */
@Component
public class PendingRecipeConsumer {

    private final JdbcClient jdbc;

    public PendingRecipeConsumer(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private record Recipe(
            long projectId, long publicationId, String code, String name,
            String cron, String zone, String conflict, String misfire,
            String publicationPolicy, OffsetDateTime startsAt, OffsetDateTime endsAt,
            Long actorId) {
    }

    /** Must run inside the transaction that inserted {@code publicationId}. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void consume(long projectId, long scenarioId, long environmentId, long publicationId) {
        boolean realMatch = jdbc.sql("""
                select exists(
                    select 1 from akis.yayin
                     where id = :publicationId and proje_id = :projectId
                       and senaryo_id = :scenarioId and ortam_id = :environmentId
                       and durum <> 'IPTAL')
                """)
                .param("publicationId", publicationId)
                .param("projectId", projectId)
                .param("scenarioId", scenarioId)
                .param("environmentId", environmentId)
                .query(Boolean.class).single();
        if (!realMatch) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "IMPORTED_RECIPE_PUBLICATION_MISMATCH",
                    "İthal tarif için aynı proje, senaryo ve ortama ait gerçek yayın bulunamadı.");
        }
        List<Recipe> recipes = jdbc.sql("""
                select r.proje_id, :publicationId as publication_id,
                       s.kod, s.ad, s.cron_ifadesi, s.zaman_dilimi,
                       s.cakisma_politikasi, s.kacirma_politikasi,
                        s.yayin_gorunum_kodu, s.baslangic_zamani, s.bitis_zamani,
                        s.olusturan_kullanici_id
                  from akis.ithal_yayin_tarifi r
                  join akis.senaryo sc
                    on sc.proje_id = r.proje_id and sc.tanim_surumu_id = r.tanim_surumu_id
                  join akis.ithal_zamanlama_tarifi s
                    on s.proje_id = r.proje_id and s.yayin_tarifi_id = r.id
                 where r.proje_id = :projectId and sc.id = :scenarioId
                   and r.ortam_id = :environmentId
                 order by s.id
                """)
                .param("publicationId", publicationId)
                .param("projectId", projectId)
                .param("scenarioId", scenarioId)
                .param("environmentId", environmentId)
                .query((rs, row) -> new Recipe(
                        rs.getLong("proje_id"), rs.getLong("publication_id"),
                        rs.getString("kod"), rs.getString("ad"),
                        rs.getString("cron_ifadesi"), rs.getString("zaman_dilimi"),
                        rs.getString("cakisma_politikasi"), rs.getString("kacirma_politikasi"),
                        rs.getString("yayin_gorunum_kodu"),
                        rs.getObject("baslangic_zamani", OffsetDateTime.class),
                        rs.getObject("bitis_zamani", OffsetDateTime.class),
                        rs.getObject("olusturan_kullanici_id", Long.class)))
                .list();
        for (Recipe recipe : recipes) {
            validate(recipe);
            Long inserted = jdbc.sql("""
                    insert into akis.zamanlama(
                        proje_id, yayin_id, kod, ad, olusturan_kullanici_id,
                        cron_ifadesi, zaman_dilimi, cakisma_politikasi, kacirma_politikasi,
                         yayin_gorunum_kodu, baslangic_zamani, bitis_zamani,
                         durum_kodu, sonraki_tetikleme_zamani)
                    values (:projectId, :publicationId, :code, :name, :actorId,
                             :cron, :zone, :conflict, :misfire, :policy,
                             :startsAt, :endsAt, 'ASKIDA', null)
                    on conflict (proje_id, kod) do nothing
                    returning id
                    """)
                    .param("projectId", recipe.projectId())
                    .param("publicationId", recipe.publicationId())
                    .param("code", recipe.code())
                    .param("name", recipe.name())
                    .param("actorId", recipe.actorId())
                    .param("cron", recipe.cron())
                    .param("zone", recipe.zone())
                    .param("conflict", recipe.conflict())
                    .param("misfire", recipe.misfire())
                    .param("policy", recipe.publicationPolicy())
                    .param("startsAt", recipe.startsAt())
                    .param("endsAt", recipe.endsAt())
                    .query(Long.class).optional().orElse(null);
            if (inserted == null && !matchesExisting(recipe)) {
                throw conflict(recipe.code());
            }
        }
    }

    private void validate(Recipe recipe) {
        if (recipe.actorId() == null || !jdbc.sql("""
                select exists(select 1 from akis.kullanici
                               where id = :actorId and durum = 'AKTIF'
                                 and devre_disi_birakilma_zamani is null)
                """).param("actorId", recipe.actorId()).query(Boolean.class).single()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "IMPORTED_SCHEDULE_ACTOR_INACTIVE",
                    "İthal zamanlama tarifini oluşturan kullanıcı etkin değil.");
        }
        ScheduleCalculator.parseCron(recipe.cron());
        ScheduleCalculator.parseZone(recipe.zone());
        if (recipe.code() == null || !recipe.code().matches("[A-Z][A-Z0-9_]{0,99}")
                || recipe.name() == null || recipe.name().isBlank()
                || !("SKIP".equals(recipe.conflict()) || "QUEUE".equals(recipe.conflict()))
                || !("SKIP".equals(recipe.misfire()) || "RUN_ONCE".equals(recipe.misfire()))
                || !("LATEST_ACTIVE".equals(recipe.publicationPolicy())
                        || "PINNED".equals(recipe.publicationPolicy()))
                || (recipe.startsAt() != null && recipe.endsAt() != null
                        && !recipe.startsAt().isBefore(recipe.endsAt()))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "IMPORTED_SCHEDULE_INVALID", "İthal zamanlama kodu veya adı geçersiz.");
        }
    }

    private boolean matchesExisting(Recipe recipe) {
        return jdbc.sql("""
                select yayin_id, ad, olusturan_kullanici_id, cron_ifadesi, zaman_dilimi,
                       cakisma_politikasi, kacirma_politikasi, yayin_gorunum_kodu,
                        baslangic_zamani, bitis_zamani,
                        durum_kodu, sonraki_tetikleme_zamani, arsivlenme_zamani
                  from akis.zamanlama
                 where proje_id = :projectId and kod = :code
                 for update
                """)
                .param("projectId", recipe.projectId())
                .param("code", recipe.code())
                .query((rs, row) ->
                        rs.getLong("yayin_id") == recipe.publicationId()
                        && Objects.equals(rs.getString("ad"), recipe.name())
                        && Objects.equals(rs.getObject("olusturan_kullanici_id", Long.class), recipe.actorId())
                        && Objects.equals(rs.getString("cron_ifadesi"), recipe.cron())
                        && Objects.equals(rs.getString("zaman_dilimi"), recipe.zone())
                        && Objects.equals(rs.getString("cakisma_politikasi"), recipe.conflict())
                        && Objects.equals(rs.getString("kacirma_politikasi"), recipe.misfire())
                        && Objects.equals(rs.getString("yayin_gorunum_kodu"), recipe.publicationPolicy())
                        && Objects.equals(rs.getObject("baslangic_zamani", OffsetDateTime.class), recipe.startsAt())
                        && Objects.equals(rs.getObject("bitis_zamani", OffsetDateTime.class), recipe.endsAt())
                        && "ASKIDA".equals(rs.getString("durum_kodu"))
                        && rs.getObject("sonraki_tetikleme_zamani") == null
                        && rs.getObject("arsivlenme_zamani") == null)
                .optional().orElse(false);
    }

    private ApiException conflict(String code) {
        return new ApiException(HttpStatus.CONFLICT,
                "IMPORTED_SCHEDULE_CODE_CONFLICT",
                "İthal zamanlama kodu mevcut farklı bir zamanlamayla çakışıyor: " + code);
    }
}
