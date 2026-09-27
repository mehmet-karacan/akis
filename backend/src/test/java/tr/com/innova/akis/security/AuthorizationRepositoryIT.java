package tr.com.innova.akis.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import tr.com.innova.akis.security.AuthorizationRepository.PrincipalIdentity;
import tr.com.innova.akis.security.AuthorizationRepository.ProjectAccess;

class AuthorizationRepositoryIT {

    private static JdbcClient jdbc;
    private static AuthorizationRepository repository;

    @BeforeAll
    static void connectToCleanBaseline() {
        String url = required("SPRING_DATASOURCE_URL");
        if (!url.matches(".*(/akis_[a-z_]*test_[0-9]+)(?:\\?.*)?$")) {
            throw new IllegalStateException("Clean RBAC test requires its generated test database.");
        }
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                url,
                required("SPRING_DATASOURCE_USERNAME"),
                required("SPRING_DATASOURCE_PASSWORD"));
        jdbc = JdbcClient.create(dataSource);
        repository = new AuthorizationRepository(jdbc);
    }

    @Test
    void grantsOnlyPermissionsOfTheActiveProjectRole() {
        Fixture fixture = projectFixture("GELISTIRICI");

        ProjectAccess edit = repository.projectAccess(
                fixture.principal(), fixture.projectUuid(), "TANIM_DUZENLE");
        ProjectAccess memberManagement = repository.projectAccess(
                fixture.principal(), fixture.projectUuid(), "UYE_YONET");

        assertTrue(edit.visible());
        assertTrue(edit.permitted());
        assertTrue(memberManagement.visible());
        assertFalse(memberManagement.permitted());
    }

    @Test
    void returnsTheEffectiveRoleProfileForTheSelectedProject() {
        Fixture fixture = projectFixture("OPERASYON");

        var grants = repository.projectGrants(fixture.principal(), fixture.projectUuid());

        assertTrue(grants.stream().allMatch(grant -> "OPERASYON".equals(grant.roleCode())));
        assertTrue(grants.stream().anyMatch(grant -> "CALISTIRMA_GORUNTULE".equals(grant.permissionCode())));
        assertFalse(grants.stream().anyMatch(grant -> "TANIM_DUZENLE".equals(grant.permissionCode())));
    }

    @Test
    void suspendedMembershipHidesProjectAndRevokedRoleRemovesPermission() {
        Fixture suspended = projectFixture("GELISTIRICI");
        jdbc.sql("""
                        update akis.proje_uyeligi
                           set durum = 'ASKIDA', askiya_alinma_zamani = current_timestamp
                         where proje_id = (select id from akis.proje where uuid = :projectUuid)
                        """)
                .param("projectUuid", suspended.projectUuid())
                .update();

        ProjectAccess hidden = repository.projectAccess(
                suspended.principal(), suspended.projectUuid(), "TANIM_DUZENLE");
        assertFalse(hidden.visible());
        assertFalse(hidden.permitted());

        Fixture revoked = projectFixture("GELISTIRICI");
        jdbc.sql("""
                        update akis.kullanici_rol
                           set iptal_zamani = current_timestamp
                         where proje_id = (select id from akis.proje where uuid = :projectUuid)
                        """)
                .param("projectUuid", revoked.projectUuid())
                .update();

        ProjectAccess visibleWithoutPermission = repository.projectAccess(
                revoked.principal(), revoked.projectUuid(), "TANIM_DUZENLE");
        assertTrue(visibleWithoutPermission.visible());
        assertFalse(visibleWithoutPermission.permitted());
    }

    @Test
    void resolvesSystemPermissionAndVisibleProjectsThroughApplicationUser() {
        Fixture fixture = projectFixture("GORUNTULEYICI");
        long userId = fixture.principal().userId();
        jdbc.sql("""
                        insert into akis.kullanici_rol(kullanici_id, rol_id, rol_kapsami)
                        select :userId, id, 'SISTEM'
                          from akis.rol
                         where kapsam = 'SISTEM' and kod = 'SISTEM_YONETICISI'
                        """)
                .param("userId", userId)
                .update();

        assertTrue(repository.hasSystemPermission(fixture.principal(), "KULLANICI_YONET"));
        assertEquals(
                java.util.Set.of(fixture.projectUuid()),
                repository.visibleProjectUuids(fixture.principal()));
        assertTrue(repository.activeProjectUuids().contains(fixture.projectUuid()));
    }

    private static Fixture projectFixture(String roleCode) {
        UUID userUuid = UUID.randomUUID();
        UUID projectUuid = UUID.randomUUID();
        String userCode = "user-" + UUID.randomUUID();

        long userId = jdbc.sql("""
                        insert into akis.kullanici(
                            uuid, kullanici_kodu, ad, gorunen_ad, eposta, durum,
                            parola, parola_degistirilme_zamani)
                        values (:uuid, :userCode, 'Repository', 'Repository Test User', :email,
                                'AKTIF', '{argon2}test', current_timestamp)
                        returning id
                        """)
                .param("uuid", userUuid)
                .param("userCode", userCode)
                .param("email", userUuid + "@example.test")
                .query(Long.class)
                .single();
        long projectId = jdbc.sql("""
                        insert into akis.proje(uuid, kod, ad)
                        values (:uuid, :code, 'Repository Test Project')
                        returning id
                        """)
                .param("uuid", projectUuid)
                .param("code", "P_" + projectUuid.toString().replace("-", "").toUpperCase())
                .query(Long.class)
                .single();
        jdbc.sql("""
                        insert into akis.proje_uyeligi(proje_id, kullanici_id)
                        values (:projectId, :userId)
                        """)
                .param("projectId", projectId)
                .param("userId", userId)
                .update();
        jdbc.sql("""
                        insert into akis.kullanici_rol(
                            kullanici_id, rol_id, rol_kapsami, proje_id)
                        select :userId, id, 'PROJE', :projectId
                          from akis.rol
                         where kapsam = 'PROJE' and kod = :roleCode
                        """)
                .param("userId", userId)
                .param("projectId", projectId)
                .param("roleCode", roleCode)
                .update();
        return new Fixture(projectUuid, new PrincipalIdentity(userId, userUuid, userCode, "test-user"));
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for the clean RBAC test.");
        }
        return value;
    }

    private record Fixture(UUID projectUuid, PrincipalIdentity principal) {
    }
}
