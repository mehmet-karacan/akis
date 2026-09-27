package tr.com.innova.akis.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import tr.com.innova.akis.identity.IdentityModels.MembershipRow;
import tr.com.innova.akis.identity.IdentityModels.ProjectRoleRef;
import tr.com.innova.akis.identity.IdentityModels.UserRow;

class JdbcIdentityStoreIT {

    private static JdbcClient jdbc;
    private static JdbcIdentityStore store;

    @BeforeAll
    static void connectToCleanBaseline() {
        String url = required("SPRING_DATASOURCE_URL");
        if (!url.matches(".*(/akis_[a-z_]*test_[0-9]+)(?:\\?.*)?$")) {
            throw new IllegalStateException("Clean identity test requires its generated test database.");
        }
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                url,
                required("SPRING_DATASOURCE_USERNAME"),
                required("SPRING_DATASOURCE_PASSWORD"));
        jdbc = JdbcClient.create(dataSource);
        store = new JdbcIdentityStore(jdbc);
    }

    @Test
    void storesApplicationUserWithoutExternalIdentity() {
        UUID userUuid = UUID.randomUUID();
        String userCode = "identity-" + userUuid;
        UserRow created = store.createUser(
                userUuid,
                userCode,
                "Identity",
                "Test User",
                "S-" + userUuid,
                userUuid + "@example.test");

        assertEquals(userUuid, created.uuid());
        assertEquals("PAROLA_BEKLIYOR", created.status());
        assertEquals("Identity Test User", created.name());
        assertTrue(store.findUser(userCode).isPresent());
    }

    @Test
    void findsUserCodeCaseInsensitively() {
        String userCode = "developer-" + UUID.randomUUID();
        UserRow created = store.createUser(
                UUID.randomUUID(), userCode, "Local", "Developer", null, null);

        assertEquals(created.uuid(), store.findUser(userCode.toUpperCase()).orElseThrow().uuid());
    }

    @Test
    void createsTemporalMembershipAndReusableProjectRoleAssignment() {
        UserRow user = store.createUser(
                UUID.randomUUID(),
                "member-" + UUID.randomUUID(),
                "Membership", "Test User", null, null);
        UUID projectUuid = UUID.randomUUID();
        long projectId = jdbc.sql("""
                        insert into akis.proje(uuid, kod, ad)
                        values (:uuid, :code, 'Identity Store Project')
                        returning id
                        """)
                .param("uuid", projectUuid)
                .param("code", "I_" + projectUuid.toString().replace("-", "").toUpperCase())
                .query(Long.class)
                .single();
        ProjectRoleRef role = store.findProjectRole("OPERASYON").orElseThrow();
        OffsetDateTime startsAt = OffsetDateTime.now(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MICROS)
                .plusMinutes(5);
        OffsetDateTime endsAt = startsAt.plusDays(7);

        MembershipRow membership = store.createMembership(
                projectId, user.id(), role.id(), UUID.randomUUID(), UUID.randomUUID(),
                startsAt, endsAt);

        assertEquals(projectUuid, membership.projectUuid());
        assertEquals(startsAt.truncatedTo(ChronoUnit.MICROS), membership.startsAt());
        assertEquals(endsAt.truncatedTo(ChronoUnit.MICROS), membership.endsAt());
        assertEquals("OPERASYON", membership.roles().getFirst().code());
        assertTrue(store.membershipExists(projectId, user.id()));
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for the clean identity test.");
        }
        return value;
    }
}
