package tr.com.innova.akis.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(
        name = "akis.security.mode",
        havingValue = "fail-closed",
        matchIfMissing = true)
class FailClosedSecurityConfiguration {

    @Bean
    SecurityFilterChain failClosedSecurityFilterChain(HttpSecurity http) throws Exception {
        SecuritySupport.failClosed(http);
        return http.build();
    }
}
