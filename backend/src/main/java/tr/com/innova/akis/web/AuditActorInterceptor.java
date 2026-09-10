package tr.com.innova.akis.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import tr.com.innova.akis.security.AuthorizationService;

@Component
final class AuditActorInterceptor implements HandlerInterceptor {

    static final String PRINCIPAL_ATTRIBUTE = AuditActorInterceptor.class.getName() + ".principal";

    private final AuthorizationService authorization;

    AuditActorInterceptor(AuthorizationService authorization) {
        this.authorization = authorization;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler) {
        request.setAttribute(PRINCIPAL_ATTRIBUTE, authorization.currentPrincipalName());
        return true;
    }
}
