package tr.com.innova.akis.identity;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tr.com.innova.akis.identity.IdentityModels.DefaultProjectRole;
import tr.com.innova.akis.identity.IdentityModels.MembershipRow;
import tr.com.innova.akis.identity.IdentityModels.ProjectRef;
import tr.com.innova.akis.identity.IdentityModels.ProjectRoleRef;
import tr.com.innova.akis.identity.IdentityModels.UserRow;
import tr.com.innova.akis.metadata.ApiException;

@Service
public class IdentityService {

    private static final Pattern SIMPLE_EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+$");
    private static final String LOCAL_BASIC_PROVIDER = "LOCAL_BASIC";

    private final IdentityStore store;
    private final Clock clock;

    @Autowired
    public IdentityService(IdentityStore store) {
        this(store, Clock.systemUTC());
    }

    IdentityService(IdentityStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    @Transactional
    public UserRow createOidcUser(
            String issuer,
            String subject,
            String name,
            String email) {
        String safeIssuer = issuer(issuer);
        String safeSubject = required(subject, "OIDC subject", 500);
        String safeName = required(name, "Ad", 200);
        String safeEmail = email(email);
        if (store.findUser(safeIssuer, safeSubject).isPresent()) {
            throw conflict("OIDC_USER_EXISTS", "OIDC kullanıcısı zaten kayıtlı.");
        }
        return store.createUser(
                UUID.randomUUID(), safeIssuer, safeSubject, safeName, safeEmail);
    }

    @Transactional(readOnly = true)
    public List<UserRow> listOidcUsers() {
        return store.listUsers();
    }

    @Transactional(readOnly = true)
    public UserRow oidcUser(UUID userUuid) {
        if (userUuid == null) {
            throw validation("Kullanıcı UUID değeri zorunludur.");
        }
        return store.findUser(userUuid)
                .orElseThrow(() -> notFound("Kullanıcı bulunamadı."));
    }

    @Transactional
    public MembershipRow createMembership(
            UUID projectUuid,
            UUID userUuid,
            DefaultProjectRole role,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt) {
        ProjectRef project = activeProject(projectUuid);
        UserRow user = activeUser(userUuid);
        if (role == null) {
            throw validation("Varsayılan proje rolü zorunludur.");
        }
        ProjectRoleRef projectRole = store.findProjectRole(role.name())
                .orElseThrow(() -> validation("Varsayılan proje rolü oluşturulmamış."));
        if (!role.name().equals(projectRole.code())) {
            throw validation("Seçilen proje rolü geçersizdir.");
        }
        if (!projectRole.enabled()) {
            throw validation("Pasif proje rolü üyeliğe atanamaz.");
        }
        if (store.membershipExists(project.id(), user.id())) {
            throw conflict("PROJECT_MEMBERSHIP_EXISTS", "Kullanıcı projeye zaten üyedir.");
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime safeStartsAt = startsAt == null ? now : startsAt;
        if (endsAt != null && endsAt.isBefore(safeStartsAt)) {
            throw validation("Üyelik bitiş zamanı başlangıç zamanından önce olamaz.");
        }
        if (endsAt != null && endsAt.isBefore(now)) {
            throw validation("Yeni aktif üyeliğin bitiş zamanı geçmişte olamaz.");
        }
        return store.createMembership(
                project.id(),
                user.id(),
                projectRole.id(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                safeStartsAt,
                endsAt);
    }

    @Transactional(readOnly = true)
    public List<MembershipRow> listMemberships(UUID projectUuid) {
        return store.listMemberships(activeProject(projectUuid).id());
    }

    @Transactional(readOnly = true)
    public MembershipRow membership(UUID projectUuid, UUID membershipUuid) {
        ProjectRef project = activeProject(projectUuid);
        if (membershipUuid == null) {
            throw validation("Üyelik UUID değeri zorunludur.");
        }
        MembershipRow membership = store.findMembership(project.id(), membershipUuid)
                .orElseThrow(() -> notFound("Proje üyeliği bulunamadı."));
        if (membership.projectId() != project.id()
                || !membership.projectUuid().equals(project.uuid())) {
            throw notFound("Proje üyeliği bulunamadı.");
        }
        return membership;
    }

    private ProjectRef activeProject(UUID projectUuid) {
        if (projectUuid == null) {
            throw validation("Proje UUID değeri zorunludur.");
        }
        ProjectRef project = store.findProject(projectUuid)
                .orElseThrow(() -> notFound("Proje bulunamadı."));
        if (!"AKTIF".equals(project.status())) {
            throw validation("Arşivlenmiş projede üyelik yönetilemez.");
        }
        return project;
    }

    private UserRow activeUser(UUID userUuid) {
        if (userUuid == null) {
            throw validation("Kullanıcı UUID değeri zorunludur.");
        }
        UserRow user = store.findUser(userUuid)
                .orElseThrow(() -> notFound("Kullanıcı bulunamadı."));
        if (!"AKTIF".equals(user.status())) {
            throw validation("Pasif kullanıcı projeye üye yapılamaz.");
        }
        return user;
    }

    private String issuer(String value) {
        String normalized = required(value, "OIDC issuer", 500);
        if (LOCAL_BASIC_PROVIDER.equals(normalized)) {
            return normalized;
        }
        try {
            URI parsed = new URI(normalized);
            String scheme = parsed.getScheme() == null
                    ? ""
                    : parsed.getScheme().toLowerCase(Locale.ROOT);
            if (!parsed.isAbsolute()
                    || !(scheme.equals("https") || scheme.equals("http"))
                    || parsed.getHost() == null
                    || parsed.getQuery() != null
                    || parsed.getFragment() != null) {
                throw validation("OIDC issuer geçerli bir HTTP(S) URI olmalıdır.");
            }
            return normalized;
        }
        catch (URISyntaxException exception) {
            throw validation("OIDC issuer geçerli bir HTTP(S) URI olmalıdır.");
        }
    }

    private String email(String value) {
        String normalized = trimToNull(value);
        if (normalized != null
                && (normalized.length() > 320 || !SIMPLE_EMAIL.matcher(normalized).matches())) {
            throw validation("E-posta adresi geçersizdir.");
        }
        return normalized;
    }

    private String required(String value, String field, int maximumLength) {
        String normalized = trimToNull(value);
        if (normalized == null || normalized.length() > maximumLength) {
            throw validation(field + " 1-" + maximumLength + " karakter olmalıdır.");
        }
        return normalized;
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private ApiException validation(String message) {
        return new ApiException(
                HttpStatus.UNPROCESSABLE_CONTENT, "IDENTITY_VALIDATION_FAILED", message);
    }

    private ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "IDENTITY_NOT_FOUND", message);
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
