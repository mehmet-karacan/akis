package tr.com.innova.akis.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "akis.security.mode", havingValue = "local", matchIfMissing = true)
class LocalAuthenticationConfiguration {

    @Bean
    LocalAuthenticationProvider localAuthenticationProvider(LocalAuthenticationService service) {
        return new LocalAuthenticationProvider(service);
    }

    @Bean
    AuthenticationManager authenticationManager(LocalAuthenticationProvider provider) {
        return new ProviderManager(provider);
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    SecurityFilterChain localSecurityFilterChain(
            HttpSecurity http,
            LocalAuthenticationProvider provider,
            SecurityContextRepository contexts,
            AbsoluteSessionTimeoutFilter absoluteSessionTimeoutFilter,
            ActiveUserSessionFilter activeUserSessionFilter) throws Exception {
        CookieCsrfTokenRepository csrf = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrf.setCookiePath("/");
        http
                .authenticationProvider(provider)
                .securityContext(security -> security
                        .securityContextRepository(contexts)
                        .requireExplicitSave(true))
                .csrf(configuration -> configuration.csrfTokenRepository(csrf))
                .sessionManagement(session -> session
                        .sessionFixation(fixation -> fixation.changeSessionId())
                        .maximumSessions(20))
                .requestCache(cache -> cache.disable())
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/api/v1/auth/csrf",
                                "/api/v1/auth/login",
                                "/api/v1/auth/password/setup").permitAll()
                        .requestMatchers("/actuator/health/**", "/error").permitAll()
                        .anyRequest().authenticated());
        http.addFilterAfter(absoluteSessionTimeoutFilter, SecurityContextHolderFilter.class);
        http.addFilterAfter(activeUserSessionFilter, AbsoluteSessionTimeoutFilter.class);
        return http.build();
    }
}
