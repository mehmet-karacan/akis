package tr.com.innova.akis.security;

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.WebAuthenticationDetails;

final class LocalAuthenticationProvider implements AuthenticationProvider {

    private final LocalAuthenticationService service;

    LocalAuthenticationProvider(LocalAuthenticationService service) {
        this.service = service;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        String password = authentication.getCredentials() == null
                ? "" : authentication.getCredentials().toString();
        ApplicationUserPrincipal principal = service.authenticate(
                authentication.getName(), password, remoteAddress(authentication));
        return UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities());
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }

    private InetAddress remoteAddress(Authentication authentication) {
        String value = authentication.getDetails() instanceof WebAuthenticationDetails details
                ? details.getRemoteAddress() : "127.0.0.1";
        try {
            return InetAddress.getByName(value);
        }
        catch (UnknownHostException exception) {
            return InetAddress.getLoopbackAddress();
        }
    }
}

