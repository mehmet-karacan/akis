package tr.com.innova.akis.security;

import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Performs authorization checks directly against the relational RBAC model.
 * Keeping these joins here prevents feature repositories from implementing
 * subtly different membership and role-validity rules.
 */
@Repository
public class AuthorizationRepository {

    private final JdbcClient jdbc;

    public AuthorizationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public ProjectAccess projectAccess(
            PrincipalIdentity principal,
            UUID projectUuid,
            String permissionCode) {
        return jdbc.sql("""
                        with active_membership as (
                            select p.id as project_id, pu.id as membership_id
                              from entegrasyon.proje p
                              join entegrasyon.proje_uyeligi pu on pu.proje_id = p.id
                              join entegrasyon.kullanici k on k.id = pu.kullanici_id
                             where p.uuid = :projectUuid
                               and p.durum_kodu = 'AKTIF'
                               and k.oidc_saglayici = :provider
                               and k.oidc_ozne = :subject
                               and k.durum_kodu = 'AKTIF'
                               and pu.durum_kodu = 'AKTIF'
                               and pu.baslangic_zamani <= current_timestamp
                               and (pu.bitis_zamani is null
                                    or pu.bitis_zamani >= current_timestamp)
                        )
                        select exists(select 1 from active_membership) as visible,
                               exists(
                                   select 1
                                     from active_membership am
                                     join entegrasyon.proje_uyeligi_rolu pur
                                       on pur.proje_id = am.project_id
                                      and pur.proje_uyeligi_id = am.membership_id
                                     join entegrasyon.proje_rolu pr
                                       on pr.proje_id = pur.proje_id
                                      and pr.id = pur.proje_rolu_id
                                      and pr.durum_kodu = 'AKTIF'
                                     join entegrasyon.proje_rolu_yetkisi pry
                                       on pry.proje_id = pr.proje_id
                                      and pry.proje_rolu_id = pr.id
                                     join entegrasyon.yetki y on y.id = pry.yetki_id
                                    where y.kod = :permissionCode
                                      and y.kapsam_kodu in ('PROJE', 'KAYNAK', 'URETIM')
                               ) as permitted
                        """)
                .param("projectUuid", projectUuid)
                .param("provider", principal.provider())
                .param("subject", principal.subject())
                .param("permissionCode", permissionCode)
                .query((rs, rowNum) -> new ProjectAccess(
                        rs.getBoolean("visible"), rs.getBoolean("permitted")))
                .single();
    }

    public boolean hasSystemPermission(
            PrincipalIdentity principal,
            String permissionCode) {
        return jdbc.sql("""
                        select exists(
                            select 1
                              from entegrasyon.kullanici k
                              join entegrasyon.kullanici_sistem_rolu ksr
                                on ksr.kullanici_id = k.id
                              join entegrasyon.sistem_rolu sr
                                on sr.id = ksr.sistem_rolu_id
                               and sr.durum_kodu = 'AKTIF'
                              join entegrasyon.sistem_rolu_yetkisi sry
                                on sry.sistem_rolu_id = sr.id
                              join entegrasyon.yetki y
                                on y.id = sry.yetki_id
                               and y.kapsam_kodu = 'SISTEM'
                             where k.oidc_saglayici = :provider
                               and k.oidc_ozne = :subject
                               and k.durum_kodu = 'AKTIF'
                               and y.kod = :permissionCode
                        )
                        """)
                .param("provider", principal.provider())
                .param("subject", principal.subject())
                .param("permissionCode", permissionCode)
                .query(Boolean.class)
                .single();
    }

    public Set<UUID> visibleProjectUuids(PrincipalIdentity principal) {
        return jdbc.sql("""
                        select p.uuid
                          from entegrasyon.proje p
                          join entegrasyon.proje_uyeligi pu on pu.proje_id = p.id
                          join entegrasyon.kullanici k on k.id = pu.kullanici_id
                         where p.durum_kodu = 'AKTIF'
                           and k.oidc_saglayici = :provider
                           and k.oidc_ozne = :subject
                           and k.durum_kodu = 'AKTIF'
                           and pu.durum_kodu = 'AKTIF'
                           and pu.baslangic_zamani <= current_timestamp
                           and (pu.bitis_zamani is null or pu.bitis_zamani >= current_timestamp)
                        """)
                .param("provider", principal.provider())
                .param("subject", principal.subject())
                .query(UUID.class)
                .set();
    }

    public Set<UUID> activeProjectUuids() {
        return jdbc.sql("select uuid from entegrasyon.proje where durum_kodu = 'AKTIF'")
                .query(UUID.class)
                .set();
    }

    public record PrincipalIdentity(String provider, String subject, String name) {
    }

    public record ProjectAccess(boolean visible, boolean permitted) {
    }
}
