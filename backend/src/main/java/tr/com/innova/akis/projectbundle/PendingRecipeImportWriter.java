package tr.com.innova.akis.projectbundle;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import tr.com.innova.akis.metadata.DefinitionType;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.GlobalBinding;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.GlobalBindingMode;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.GlobalResourceType;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.ProjectBundle;
import tr.com.innova.akis.projectbundle.ProjectBundleModels.PublicationEntry;

/** Stages only declarative recipes after target definitions and versions exist. */
@Component
class PendingRecipeImportWriter {

    private final JdbcClient jdbc;
    private final PendingRecipeRepository recipes;

    PendingRecipeImportWriter(JdbcClient jdbc, PendingRecipeRepository recipes) {
        this.jdbc = jdbc;
        this.recipes = recipes;
    }

    void stage(long projectId, ProjectBundle bundle,
            Map<DefinitionType, Map<String, Long>> definitionIdsByCode,
            List<GlobalBinding> bindings, long actorId) {
        if (bundle.publications() == null || bundle.publications().isEmpty()) {
            if (bundle.schedules() != null && !bundle.schedules().isEmpty()) {
                throw unresolved("schedules.publicationSelection");
            }
            return;
        }

        Map<String, UUID> environmentTargets = new HashMap<>();
        for (GlobalBinding binding : bindings == null ? List.<GlobalBinding>of() : bindings) {
            if (binding != null && binding.type() == GlobalResourceType.ENVIRONMENT
                    && binding.mode() == GlobalBindingMode.BIND_EXISTING
                    && binding.targetUuid() != null) {
                environmentTargets.put(binding.sourceCode(), binding.targetUuid());
            }
        }

        Map<PublicationEntry, Long> recipeIds = new HashMap<>();
        for (PublicationEntry publication : bundle.publications()) {
            if (publication == null) {
                throw unresolved("publications");
            }
            Map<String, Long> definitions = definitionIdsByCode.get(publication.definitionType());
            Long definitionId = definitions == null ? null : definitions.get(publication.definitionCode());
            if (definitionId == null) {
                throw unresolved("publications.definitionCode");
            }
            long versionId = jdbc.sql("""
                    select id from akis.tanim_surumu
                     where proje_id = :project and tanim_id = :definition and surum_no = :version
                    """)
                    .param("project", projectId)
                    .param("definition", definitionId)
                    .param("version", publication.definitionVersionNumber())
                    .query(Long.class).optional()
                    .orElseThrow(() -> unresolved("publications.definitionVersionNumber"));
            UUID environmentUuid = environmentTargets.get(publication.environmentCode());
            if (environmentUuid == null) {
                throw unresolved("publications.environmentCode");
            }
            long environmentId = jdbc.sql("select id from akis.ortam where uuid = :uuid")
                    .param("uuid", environmentUuid)
                    .query(Long.class).optional()
                    .orElseThrow(() -> unresolved("publications.environmentCode"));
            long recipeId = recipes.insertPendingPublicationRecipe(
                    projectId, definitionId, versionId, environmentId,
                    bundle.checksum(), actorId);
            recipeIds.put(publication, recipeId);
        }

        for (var schedule : bundle.schedules() == null ? List.<ProjectBundleModels.ScheduleEntry>of()
                : bundle.schedules()) {
            if (schedule == null) {
                throw unresolved("schedules");
            }
            Long recipeId = recipeIds.get(schedule.publicationSelection());
            if (recipeId == null) {
                throw unresolved("schedules.publicationSelection");
            }
            recipes.insertPendingScheduleRecipe(
                    projectId, recipeId, schedule.code(), schedule.name(),
                    schedule.cronExpression(), schedule.timeZone(),
                    schedule.conflictPolicy(), schedule.misfirePolicy(),
                    schedule.publicationPolicy(), schedule.startsAt(), schedule.endsAt(),
                    bundle.checksum(), actorId);
        }
    }

    private ProjectBundleException unresolved(String path) {
        return new ProjectBundleException(
                HttpStatus.UNPROCESSABLE_CONTENT, "BUNDLE_IMPORT_RECIPE_UNRESOLVED",
                "Yayın veya zamanlama tarifi hedefte çözümlenemedi: " + path,
                null);
    }
}
