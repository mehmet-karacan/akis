package tr.com.innova.akis.identity;

import static tr.com.innova.akis.security.PermissionCodes.IDENTITY_USER_PROVISION;
import static tr.com.innova.akis.security.PermissionCodes.PROJECT_MEMBERSHIP_MANAGE;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tr.com.innova.akis.identity.IdentityModels.DefaultProjectRole;
import tr.com.innova.akis.identity.IdentityModels.MembershipRow;
import tr.com.innova.akis.identity.IdentityModels.ProjectRoleView;
import tr.com.innova.akis.identity.IdentityModels.UserRow;
import tr.com.innova.akis.security.AuthorizationService;

@RestController
@RequestMapping("/api/v1")
final class IdentityController {

    private final IdentityService service;
    private final AuthorizationService authorization;

    IdentityController(IdentityService service, AuthorizationService authorization) {
        this.service = service;
        this.authorization = authorization;
    }

    @PostMapping("/identity/users")
    ResponseEntity<UserView> createOidcUser(
            @Valid @RequestBody CreateOidcUserRequest request) {
        authorization.requireSystemPermission(IDENTITY_USER_PROVISION);
        UserRow user = service.createOidcUser(
                request.issuer(), request.subject(), request.name(), request.email());
        return ResponseEntity.created(URI.create("/api/v1/identity/users/" + user.uuid()))
                .body(UserView.from(user));
    }

    @GetMapping("/identity/users")
    List<UserView> listOidcUsers() {
        authorization.requireSystemPermission(IDENTITY_USER_PROVISION);
        return service.listOidcUsers().stream().map(UserView::from).toList();
    }

    @GetMapping("/identity/users/{userUuid}")
    UserView oidcUser(@PathVariable UUID userUuid) {
        authorization.requireSystemPermission(IDENTITY_USER_PROVISION);
        return UserView.from(service.oidcUser(userUuid));
    }

    @PostMapping("/projects/{projectUuid}/memberships")
    ResponseEntity<MembershipView> createMembership(
            @PathVariable UUID projectUuid,
            @Valid @RequestBody CreateMembershipRequest request) {
        authorization.requireProjectPermission(
                projectUuid, PROJECT_MEMBERSHIP_MANAGE);
        MembershipRow membership = service.createMembership(
                projectUuid,
                request.userUuid(),
                request.role(),
                request.startsAt(),
                request.endsAt());
        return ResponseEntity.created(URI.create(
                        "/api/v1/projects/" + projectUuid
                                + "/memberships/" + membership.uuid()))
                .body(MembershipView.from(membership));
    }

    @GetMapping("/projects/{projectUuid}/memberships")
    List<MembershipView> listMemberships(@PathVariable UUID projectUuid) {
        authorization.requireProjectPermission(
                projectUuid, PROJECT_MEMBERSHIP_MANAGE);
        return service.listMemberships(projectUuid).stream()
                .map(MembershipView::from)
                .toList();
    }

    @GetMapping("/projects/{projectUuid}/memberships/{membershipUuid}")
    MembershipView membership(
            @PathVariable UUID projectUuid,
            @PathVariable UUID membershipUuid) {
        authorization.requireProjectPermission(
                projectUuid, PROJECT_MEMBERSHIP_MANAGE);
        return MembershipView.from(service.membership(projectUuid, membershipUuid));
    }

    record CreateOidcUserRequest(
            @NotBlank String issuer,
            @NotBlank String subject,
            @NotBlank String name,
            @Email String email) {
    }

    record CreateMembershipRequest(
            @NotNull UUID userUuid,
            @NotNull DefaultProjectRole role,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt) {
    }

    record UserView(
            UUID uuid,
            String issuer,
            String subject,
            String status,
            String name,
            String email,
            OffsetDateTime createdAt) {

        static UserView from(UserRow row) {
            return new UserView(
                    row.uuid(), row.issuer(), row.subject(), row.status(), row.name(),
                    row.email(), row.createdAt());
        }
    }

    record MembershipView(
            UUID uuid,
            UUID projectUuid,
            UUID userUuid,
            String status,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            long version,
            List<ProjectRoleView> roles) {

        static MembershipView from(MembershipRow row) {
            return new MembershipView(
                    row.uuid(), row.projectUuid(), row.userUuid(), row.status(),
                    row.startsAt(), row.endsAt(), row.version(), List.copyOf(row.roles()));
        }
    }
}
