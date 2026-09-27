package tr.com.innova.akis.security;

import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
class PasswordEncodingConfiguration {

    @Bean
    PasswordEncoder applicationPasswordEncoder() {
        PasswordEncoder argon2id = new Argon2PasswordEncoder(16, 32, 1, 65_536, 3);
        return new DelegatingPasswordEncoder("argon2", Map.of("argon2", argon2id));
    }
}
