package tr.com.innova.akis.projectbundle;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Durable staging for a v3 bundle's publication/schedule recipes after import.
 * Rows are anchored to real target-side (definition, definition version,
 * environment) ids; there is deliberately no fake publication row, no
 * schedule activation, and no runtime evidence copy here. Later integration
 * work matches these against real publications and creates the real rows.
 */
@Repository
class PendingRecipeRepository {

    private final JdbcClient jdbc;

    PendingRecipeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    long insertPendingPublicationRecipe(
            long projectId, long definitionId, long definitionVersionId, long environmentId,
            String bundleChecksum, Long actorId) {
        return jdbc.sql("""
                        insert into akis.ithal_yayin_tarifi(
                            proje_id, tanim_id, tanim_surumu_id, ortam_id,
                            kaynak_bundle_checksum, olusturan_kullanici_id)
                        values (:projectId, :definitionId, :definitionVersionId, :environmentId,
                                :checksum, :actorId)
                        returning id
                        """)
                .param("projectId", projectId)
                .param("definitionId", definitionId)
                .param("definitionVersionId", definitionVersionId)
                .param("environmentId", environmentId)
                .param("checksum", bundleChecksum)
                .param("actorId", actorId, Types.BIGINT)
                .query(Long.class)
                .single();
    }

    long insertPendingScheduleRecipe(
            long projectId, long publicationRecipeId, String code, String name,
            String cronExpression, String timeZone, String conflictPolicy, String misfirePolicy,
            String publicationViewPolicy, OffsetDateTime startsAt, OffsetDateTime endsAt,
            String bundleChecksum, Long actorId) {
        return jdbc.sql("""
                        insert into akis.ithal_zamanlama_tarifi(
                            proje_id, yayin_tarifi_id, kod, ad, cron_ifadesi, zaman_dilimi,
                            cakisma_politikasi, kacirma_politikasi, yayin_gorunum_kodu,
                            baslangic_zamani, bitis_zamani,
                            kaynak_bundle_checksum, olusturan_kullanici_id)
                        values (:projectId, :publicationRecipeId, :code, :name, :cron, :zone,
                                :conflict, :misfire, :viewPolicy, :startsAt, :endsAt, :checksum, :actorId)
                        returning id
                        """)
                .param("projectId", projectId)
                .param("publicationRecipeId", publicationRecipeId)
                .param("code", code)
                .param("name", name)
                .param("cron", cronExpression)
                .param("zone", timeZone)
                .param("conflict", conflictPolicy)
                .param("misfire", misfirePolicy)
                .param("viewPolicy", publicationViewPolicy)
                .param("startsAt", startsAt).param("endsAt", endsAt)
                .param("checksum", bundleChecksum)
                .param("actorId", actorId, Types.BIGINT)
                .query(Long.class)
                .single();
    }

    long insertPendingScheduleRecipe(
            long projectId, long publicationRecipeId, String code, String name,
            String cronExpression, String timeZone, String conflictPolicy, String misfirePolicy,
            String publicationViewPolicy, String bundleChecksum, Long actorId) {
        return insertPendingScheduleRecipe(projectId, publicationRecipeId, code, name,
                cronExpression, timeZone, conflictPolicy, misfirePolicy, publicationViewPolicy,
                null, null, bundleChecksum, actorId);
    }

    List<PendingPublicationRecipeRow> findPendingPublicationRecipes(long projectId) {
        return jdbc.sql("""
                        select id, uuid, proje_id, tanim_id, tanim_surumu_id, ortam_id,
                               kaynak_bundle_checksum, olusturulma_zamani
                          from akis.ithal_yayin_tarifi
                         where proje_id = :projectId
                         order by id
                        """)
                .param("projectId", projectId)
                .query((rs, rowNum) -> new PendingPublicationRecipeRow(
                        rs.getLong("id"), rs.getObject("uuid", UUID.class), rs.getLong("proje_id"),
                        rs.getLong("tanim_id"), rs.getLong("tanim_surumu_id"), rs.getLong("ortam_id"),
                        rs.getString("kaynak_bundle_checksum"),
                        rs.getObject("olusturulma_zamani", OffsetDateTime.class)))
                .list();
    }

    List<PendingScheduleRecipeRow> findPendingScheduleRecipes(long projectId) {
        return jdbc.sql("""
                        select id, uuid, proje_id, yayin_tarifi_id, kod, ad, cron_ifadesi,
                               zaman_dilimi, cakisma_politikasi, kacirma_politikasi,
                               yayin_gorunum_kodu, baslangic_zamani, bitis_zamani,
                               kaynak_bundle_checksum, olusturulma_zamani
                          from akis.ithal_zamanlama_tarifi
                         where proje_id = :projectId
                         order by id
                        """)
                .param("projectId", projectId)
                .query((rs, rowNum) -> new PendingScheduleRecipeRow(
                        rs.getLong("id"), rs.getObject("uuid", UUID.class), rs.getLong("proje_id"),
                        rs.getLong("yayin_tarifi_id"), rs.getString("kod"), rs.getString("ad"),
                        rs.getString("cron_ifadesi"), rs.getString("zaman_dilimi"),
                        rs.getString("cakisma_politikasi"), rs.getString("kacirma_politikasi"),
                        rs.getString("yayin_gorunum_kodu"),
                        rs.getObject("baslangic_zamani", OffsetDateTime.class),
                        rs.getObject("bitis_zamani", OffsetDateTime.class),
                        rs.getString("kaynak_bundle_checksum"),
                        rs.getObject("olusturulma_zamani", OffsetDateTime.class)))
                .list();
    }

    record PendingPublicationRecipeRow(
            long id, UUID uuid, long projectId, long definitionId, long definitionVersionId,
            long environmentId, String bundleChecksum, OffsetDateTime createdAt) {
    }

    record PendingScheduleRecipeRow(
            long id, UUID uuid, long projectId, long publicationRecipeId, String code, String name,
            String cronExpression, String timeZone, String conflictPolicy, String misfirePolicy,
            String publicationViewPolicy, OffsetDateTime startsAt, OffsetDateTime endsAt,
            String bundleChecksum, OffsetDateTime createdAt) {
    }
}
