package tr.com.innova.akis.security;

import java.util.Locale;

import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;

final class SecuritySupport {

    private SecuritySupport() {
    }

    static void authenticated(HttpSecurity http) throws Exception {
        base(http);
        http.authorizeHttpRequests(authorize -> authorize
                .requestMatchers(HttpMethod.GET, "/actuator/health/**").permitAll()
                .requestMatchers("/error").permitAll()
                .anyRequest().authenticated());
    }

    static void failClosed(HttpSecurity http) throws Exception {
        base(http);
        http.authorizeHttpRequests(authorize -> authorize
                .requestMatchers(HttpMethod.GET, "/actuator/health/**").permitAll()
                .requestMatchers("/error").permitAll()
                .anyRequest().denyAll());
    }

    private static void base(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    }

    static String required(String value, String label) {
        if (value == null || value.isBlank() || value.equalsIgnoreCase("change-me")) {
            throw new IllegalStateException(label + " must be configured securely.");
        }
        return value;
    }

    static String mode(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    static void requireLoopback(String address) {
        String normalized = address == null ? "" : address.trim().toLowerCase(Locale.ROOT);
        if (!normalized.equals("127.0.0.1")
                && !normalized.equals("localhost")
                && !normalized.equals("::1")) {
            throw new IllegalStateException(
                    "Development security mode requires a loopback server.address.");
        }
    }
}
