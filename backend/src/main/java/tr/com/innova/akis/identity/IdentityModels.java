package tr.com.innova.akis.identity;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

final class IdentityModels {

    private IdentityModels() {
    }

    enum DefaultProjectRole {
        PROJE_YONETICISI,
        GELISTIRICI,
        IZLEYICI
    }

    record UserRow(
            long id,
            UUID uuid,
            String issuer,
            String subject,
            String status,
            String name,
            String email,
            OffsetDateTime createdAt) {
    }

    record ProjectRef(long id, UUID uuid, String status) {
    }

    record ProjectRoleRef(
            long id,
            UUID uuid,
            long projectId,
            String code,
            String status) {
    }

    record MembershipRow(
            long id,
            UUID uuid,
            long projectId,
            UUID projectUuid,
            long userId,
            UUID userUuid,
            String status,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            long version,
            List<ProjectRoleView> roles) {
    }

    record ProjectRoleView(UUID uuid, String code) {
    }
}
