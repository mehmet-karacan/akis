package tr.com.innova.akis.execution;

import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import tr.com.innova.akis.metadata.ApiException;

@Component
final class RunStateMachine {

    private static final Set<String> TERMINAL_STATES = Set.of(
            "YENIDEN_DENENEBILIR", "MUDAHALE_GEREKLI",
            "BASARILI", "BASARISIZ", "IPTAL");

    CancellationDecision queuedCancellation(String currentStatus) {
        if ("IPTAL".equals(currentStatus)) {
            return CancellationDecision.ALREADY_CANCELLED;
        }
        if ("BEKLIYOR".equals(currentStatus)) {
            return CancellationDecision.TRANSITION;
        }
        throw new ApiException(
                HttpStatus.CONFLICT,
                "RUN_NOT_CANCELLABLE",
                "Yalnız bekleyen çalıştırma güvenli biçimde iptal edilebilir.");
    }

    boolean terminal(String status) {
        return TERMINAL_STATES.contains(status);
    }

    enum CancellationDecision {
        TRANSITION,
        ALREADY_CANCELLED
    }
}
