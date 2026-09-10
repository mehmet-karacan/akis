package tr.com.innova.akis.publication;

import static tr.com.innova.akis.security.PermissionCodes.PUBLICATION_APPROVE;
import static tr.com.innova.akis.security.PermissionCodes.PUBLICATION_CREATE;
import static tr.com.innova.akis.security.PermissionCodes.PUBLICATION_READ;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import tr.com.innova.akis.publication.PublicationModels.ApprovalResult;
import tr.com.innova.akis.publication.PublicationModels.ApprovalRow;
import tr.com.innova.akis.publication.PublicationModels.CreateResult;
import tr.com.innova.akis.publication.PublicationModels.PublicationRow;
import tr.com.innova.akis.security.AuthorizationService;

@RestController
@RequestMapping("/api/v1/projects/{projectUuid}/publications")
final class PublicationController {

    private final PublicationService service;
    private final PublicationActorResolver actorResolver;
    private final AuthorizationService authorization;

    PublicationController(
            PublicationService service,
            PublicationActorResolver actorResolver,
            AuthorizationService authorization) {
        this.service = service;
        this.actorResolver = actorResolver;
        this.authorization = authorization;
    }

    @PostMapping
    ResponseEntity<PublicationView> create(
            @PathVariable UUID projectUuid,
            @Valid @RequestBody CreatePublicationRequest request) {
        authorization.requireProjectPermission(projectUuid, PUBLICATION_CREATE);
        CreateResult result = service.create(
                projectUuid, request.scenarioUuid(), request.environmentUuid());
        PublicationView view = PublicationView.from(result.publication());
        if (!result.created()) {
            return ResponseEntity.ok(view);
        }
        return ResponseEntity.created(URI.create(
                        "/api/v1/projects/" + projectUuid
                                + "/publications/" + view.uuid()))
                .body(view);
    }

    @GetMapping
    List<PublicationView> list(@PathVariable UUID projectUuid) {
        authorization.requireProjectPermission(projectUuid, PUBLICATION_READ);
        return service.list(projectUuid).stream().map(PublicationView::from).toList();
    }

    @GetMapping("/{publicationUuid}")
    PublicationView get(
            @PathVariable UUID projectUuid,
            @PathVariable UUID publicationUuid) {
        authorization.requireProjectPermission(projectUuid, PUBLICATION_READ);
        return PublicationView.from(service.get(projectUuid, publicationUuid));
    }

    @PostMapping("/{publicationUuid}/approvals")
    ResponseEntity<ApprovalDecisionView> decide(
            @PathVariable UUID projectUuid,
            @PathVariable UUID publicationUuid,
            @Valid @RequestBody ApprovalDecisionRequest request) {
        authorization.requireProjectPermission(projectUuid, PUBLICATION_APPROVE);
        ApprovalResult result = service.decide(
                projectUuid, publicationUuid, actorResolver.currentActor(),
                request.decision(), request.reason());
        ApprovalDecisionView view = ApprovalDecisionView.from(result);
        if (!result.created()) {
            return ResponseEntity.ok(view);
        }
        return ResponseEntity.created(URI.create(
                        "/api/v1/projects/" + projectUuid
                                + "/publications/" + publicationUuid
                                + "/approvals/" + result.approval().uuid()))
                .body(view);
    }

    record CreatePublicationRequest(
            @NotNull UUID scenarioUuid,
            @NotNull UUID environmentUuid) {
    }

    record ApprovalDecisionRequest(
            @NotBlank String decision,
            String reason) {
    }

    record PublicationView(
            UUID uuid,
            UUID scenarioUuid,
            UUID definitionUuid,
            UUID definitionVersionUuid,
            UUID environmentUuid,
            String environmentCode,
            String environmentRisk,
            int publicationNumber,
            String status,
            String releaseHash,
            String dependencySummary,
            JsonNode physicalManifest,
            OffsetDateTime publishedAt,
            OffsetDateTime createdAt,
            long version) {

        static PublicationView from(PublicationRow row) {
            return new PublicationView(
                    row.uuid(), row.scenarioUuid(), row.definitionUuid(),
                    row.definitionVersionUuid(), row.environmentUuid(),
                    row.environmentCode(), row.environmentRisk(),
                    row.publicationNumber(), row.status(), row.releaseHash(),
                    row.dependencySummary(), row.physicalManifest(),
                    row.publishedAt(), row.createdAt(), row.version());
        }
    }

    record ApprovalView(
            UUID uuid,
            UUID publicationUuid,
            UUID actorUuid,
            String actorName,
            String decision,
            OffsetDateTime decidedAt,
            String reason) {

        static ApprovalView from(ApprovalRow row) {
            return new ApprovalView(
                    row.uuid(), row.publicationUuid(), row.actorUuid(), row.actorName(),
                    row.decision(), row.decidedAt(), row.reason());
        }
    }

    record ApprovalDecisionView(
            PublicationView publication,
            ApprovalView approval) {

        static ApprovalDecisionView from(ApprovalResult result) {
            return new ApprovalDecisionView(
                    PublicationView.from(result.publication()),
                    ApprovalView.from(result.approval()));
        }
    }
}
