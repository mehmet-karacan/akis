package tr.com.innova.akis.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "akis.security.mode", havingValue = "development")
class DevelopmentSecurityConfiguration {

    @Bean
    SecurityFilterChain developmentSecurityFilterChain(HttpSecurity http) throws Exception {
        SecuritySupport.authenticated(http);
        http.httpBasic(basic -> { });
        return http.build();
    }

    @Bean
    UserDetailsService developmentUsers(
            @Value("${akis.security.development.username:}") String username,
            @Value("${akis.security.development.password:}") String password,
            @Value("${server.address:}") String serverAddress) {
        SecuritySupport.requireLoopback(serverAddress);
        String safeUsername = SecuritySupport.required(username, "AKIS_DEV_USERNAME");
        String safePassword = SecuritySupport.required(password, "AKIS_DEV_PASSWORD");
        PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        return new InMemoryUserDetailsManager(User.withUsername(safeUsername)
                .password(encoder.encode(safePassword))
                .roles("LOCAL_DEVELOPER")
                .build());
    }
}
