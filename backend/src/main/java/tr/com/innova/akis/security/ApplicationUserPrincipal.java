package tr.com.innova.akis.security;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/** Trusted application identity stored in the server-side session. */
public record ApplicationUserPrincipal(
        long userId,
        UUID userUuid,
        String userCode,
        String displayName,
        String passwordHash,
        boolean enabled) implements UserDetails, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of();
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return userCode;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}

