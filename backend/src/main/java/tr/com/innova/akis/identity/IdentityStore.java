package tr.com.innova.akis.identity;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import tr.com.innova.akis.identity.IdentityModels.MembershipRow;
import tr.com.innova.akis.identity.IdentityModels.ProjectRef;
import tr.com.innova.akis.identity.IdentityModels.ProjectRoleRef;
import tr.com.innova.akis.identity.IdentityModels.UserRow;

interface IdentityStore {

    UserRow createUser(UUID uuid, String issuer, String subject, String name, String email);

    List<UserRow> listUsers();

    Optional<UserRow> findUser(UUID userUuid);

    Optional<UserRow> findUser(String issuer, String subject);

    Optional<ProjectRef> findProject(UUID projectUuid);

    Optional<ProjectRoleRef> findProjectRole(String roleCode);

    boolean membershipExists(long projectId, long userId);

    MembershipRow createMembership(
            long projectId,
            long userId,
            long projectRoleId,
            UUID membershipUuid,
            UUID membershipRoleUuid,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt);

    List<MembershipRow> listMemberships(long projectId);

    Optional<MembershipRow> findMembership(long projectId, UUID membershipUuid);
}
