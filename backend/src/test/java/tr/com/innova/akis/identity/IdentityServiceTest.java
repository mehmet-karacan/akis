package tr.com.innova.akis.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import tr.com.innova.akis.identity.IdentityModels.DefaultProjectRole;
import tr.com.innova.akis.identity.IdentityModels.MembershipRow;
import tr.com.innova.akis.identity.IdentityModels.ProjectRef;
import tr.com.innova.akis.identity.IdentityModels.ProjectRoleRef;
import tr.com.innova.akis.identity.IdentityModels.ProjectRoleView;
import tr.com.innova.akis.identity.IdentityModels.UserRow;
import tr.com.innova.akis.metadata.ApiException;

class IdentityServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-11T09:00:00Z");
    private static final UUID PROJECT_UUID = UUID.randomUUID();
    private static final UUID USER_UUID = UUID.randomUUID();

    @Test
    void createsNormalizedApplicationUserWithoutExposingNumericIdentity() {
        FakeStore store = new FakeStore();
        IdentityService service = service(store);

        UserRow created = service.createUser(
                "MEHMET.KARACAN", " Mehmet ", " Karacan ", " 12345 ",
                " mehmet@example.com ");

        assertEquals("mehmet.karacan", created.userCode());
        assertEquals("PAROLA_BEKLIYOR", created.status());
        assertEquals("Mehmet Karacan", created.name());
        assertEquals("12345", created.employeeNumber());
        assertEquals("mehmet@example.com", created.email());
        assertTrue(store.createdUserUuid != null);
        assertFalse(hasNumericIdentifier(IdentityController.UserView.class));
        assertFalse(hasNumericIdentifier(IdentityController.MembershipView.class));
    }

    @Test
    void rejectsDuplicateUserCodeAndInvalidEmail() {
        FakeStore store = new FakeStore();
        store.userByCode = Optional.of(store.user);
        IdentityService service = service(store);

        ApiException duplicate = assertThrows(ApiException.class, () -> service.createUser(
                "mehmet", "Mehmet", "Karacan", null, null));
        store.userByCode = Optional.empty();
        ApiException invalidEmail = assertThrows(ApiException.class, () -> service.createUser(
                "ayse", "Ayşe", null, null, "gecersiz"));

        assertEquals(HttpStatus.CONFLICT, duplicate.status());
        assertEquals("USER_CODE_EXISTS", duplicate.code());
        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, invalidEmail.status());
    }

    @Test
    void createsMembershipWithDefaultRoleAndServerTime() {
        FakeStore store = new FakeStore();
        IdentityService service = service(store);

        MembershipRow created = service.createMembership(
                PROJECT_UUID, USER_UUID, DefaultProjectRole.GELISTIRICI, null,
                OffsetDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC));

        assertEquals(10L, store.createdProjectId);
        assertEquals(20L, store.createdUserId);
        assertEquals(30L, store.createdRoleId);
        assertEquals(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC), store.createdStartsAt);
        assertEquals(PROJECT_UUID, created.projectUuid());
        assertEquals(USER_UUID, created.userUuid());
        assertEquals("GELISTIRICI", created.roles().getFirst().code());
    }

    @Test
    void exposesOperationAndViewerAsAssignableDefaultProjectRoles() {
        assertEquals(DefaultProjectRole.OPERASYON,
                DefaultProjectRole.valueOf("OPERASYON"));
        assertEquals(DefaultProjectRole.GORUNTULEYICI,
                DefaultProjectRole.valueOf("GORUNTULEYICI"));
    }

    @Test
    void rejectsRoleReturnedWithAnotherCode() {
        FakeStore store = new FakeStore();
        store.role = new ProjectRoleRef(
                30, UUID.randomUUID(), "OPERASYON", true);
        IdentityService service = service(store);

        ApiException error = assertThrows(ApiException.class, () -> service.createMembership(
                PROJECT_UUID, USER_UUID, DefaultProjectRole.GELISTIRICI, null, null));

        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, error.status());
        assertEquals("IDENTITY_VALIDATION_FAILED", error.code());
    }

    @Test
    void rejectsInactivePrincipalsAndInvalidDateRange() {
        FakeStore inactiveUserStore = new FakeStore();
        inactiveUserStore.user = user("PASIF");
        ApiException inactiveUser = assertThrows(ApiException.class,
                () -> service(inactiveUserStore).createMembership(
                        PROJECT_UUID, USER_UUID, DefaultProjectRole.GORUNTULEYICI, null, null));

        FakeStore dateStore = new FakeStore();
        OffsetDateTime starts = OffsetDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC);
        OffsetDateTime ends = starts.minusSeconds(1);
        ApiException invalidDates = assertThrows(ApiException.class,
                () -> service(dateStore).createMembership(
                        PROJECT_UUID, USER_UUID, DefaultProjectRole.GORUNTULEYICI, starts, ends));

        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, inactiveUser.status());
        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, invalidDates.status());
        assertEquals(0, dateStore.createdMembershipCount);
    }

    @Test
    void rejectsMembershipThatDoesNotBelongToRequestedProject() {
        FakeStore store = new FakeStore();
        UUID membershipUuid = UUID.randomUUID();
        store.membership = Optional.of(new MembershipRow(
                40, membershipUuid, 999, UUID.randomUUID(), 20, USER_UUID,
                "AKTIF", OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC), null, 1,
                List.of()));

        ApiException error = assertThrows(ApiException.class,
                () -> service(store).membership(PROJECT_UUID, membershipUuid));

        assertEquals(HttpStatus.NOT_FOUND, error.status());
        assertEquals("IDENTITY_NOT_FOUND", error.code());
    }

    private IdentityService service(FakeStore store) {
        return new IdentityService(store, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static UserRow user(String status) {
        return new UserRow(
                20, USER_UUID, "mehmet", status, "Mehmet", "Karacan", "12345",
                "mehmet@example.com", OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
    }

    private boolean hasNumericIdentifier(Class<?> type) {
        return List.of(type.getRecordComponents()).stream()
                .anyMatch(component -> (component.getName().equals("id")
                                || component.getName().endsWith("Id"))
                        && (component.getType().equals(long.class)
                                || component.getType().equals(Long.class)));
    }

    private static final class FakeStore implements IdentityStore {

        private final ProjectRef project = new ProjectRef(10, PROJECT_UUID, "AKTIF");
        private UserRow user = user("AKTIF");
        private ProjectRoleRef role = new ProjectRoleRef(
                30, UUID.randomUUID(), "GELISTIRICI", true);
        private Optional<UserRow> userByCode = Optional.empty();
        private Optional<MembershipRow> membership = Optional.empty();
        private UUID createdUserUuid;
        private long createdProjectId;
        private long createdUserId;
        private long createdRoleId;
        private OffsetDateTime createdStartsAt;
        private int createdMembershipCount;

        @Override
        public UserRow createUser(
                UUID uuid, String userCode, String firstName, String lastName,
                String employeeNumber, String email) {
            createdUserUuid = uuid;
            user = new UserRow(
                    20, uuid, userCode, "PAROLA_BEKLIYOR", firstName, lastName,
                    employeeNumber, email,
                    OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
            return user;
        }

        @Override
        public List<UserRow> listUsers() {
            return List.of(user);
        }

        @Override
        public Optional<UserRow> findUser(UUID userUuid) {
            return USER_UUID.equals(userUuid) ? Optional.of(user) : Optional.empty();
        }

        @Override
        public Optional<UserRow> findUser(String userCode) {
            return userByCode;
        }

        @Override
        public Optional<ProjectRef> findProject(UUID projectUuid) {
            return PROJECT_UUID.equals(projectUuid) ? Optional.of(project) : Optional.empty();
        }

        @Override
        public Optional<ProjectRoleRef> findProjectRole(String roleCode) {
            return Optional.of(new ProjectRoleRef(
                    role.id(), role.uuid(), role.code(), role.enabled()));
        }

        @Override
        public boolean membershipExists(long projectId, long userId) {
            return false;
        }

        @Override
        public MembershipRow createMembership(
                long projectId,
                long userId,
                long projectRoleId,
                UUID membershipUuid,
                UUID membershipRoleUuid,
                OffsetDateTime startsAt,
                OffsetDateTime endsAt) {
            createdProjectId = projectId;
            createdUserId = userId;
            createdRoleId = projectRoleId;
            createdStartsAt = startsAt;
            createdMembershipCount++;
            return new MembershipRow(
                    40, membershipUuid, projectId, PROJECT_UUID, userId, USER_UUID,
                    "AKTIF", startsAt, endsAt, 1,
                    new ArrayList<>(List.of(new ProjectRoleView(role.uuid(), role.code()))));
        }

        @Override
        public List<MembershipRow> listMemberships(long projectId) {
            return membership.stream().toList();
        }

        @Override
        public Optional<MembershipRow> findMembership(long projectId, UUID membershipUuid) {
            return membership;
        }
    }
}
