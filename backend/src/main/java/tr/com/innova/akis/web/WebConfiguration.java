package tr.com.innova.akis.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
class WebConfiguration implements WebMvcConfigurer {

    private final AuditActorInterceptor auditActorInterceptor;

    WebConfiguration(AuditActorInterceptor auditActorInterceptor) {
        this.auditActorInterceptor = auditActorInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(auditActorInterceptor).addPathPatterns("/api/v1/**");
    }
}
