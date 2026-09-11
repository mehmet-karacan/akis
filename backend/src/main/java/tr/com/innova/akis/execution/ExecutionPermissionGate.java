package tr.com.innova.akis.execution;

import java.util.UUID;

import org.springframework.stereotype.Component;

import tr.com.innova.akis.security.AuthorizationService;
import static tr.com.innova.akis.security.PermissionCodes.PRODUCTION_RUN;

interface ExecutionPermissionGate {

    void requireProductionRun(UUID projectUuid);
}

@Component
final class AuthorizationExecutionPermissionGate implements ExecutionPermissionGate {

    private final AuthorizationService authorization;

    AuthorizationExecutionPermissionGate(AuthorizationService authorization) {
        this.authorization = authorization;
    }

    @Override
    public void requireProductionRun(UUID projectUuid) {
        authorization.requireProjectPermission(projectUuid, PRODUCTION_RUN);
    }
}
