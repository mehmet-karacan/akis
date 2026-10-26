package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tr.com.innova.akis.execution.RunLeasePort.RunLeaseToken;
import tr.com.innova.akis.execution.RunLeasePort.WorkerIdentity;

class JdbcRunLeaseStoreTransactionContractTest {

    @Test
    void everyLeaseMutationRequiresAnIndependentTransaction() throws Exception {
        assertRequiresNew(method("claimForPreflight", WorkerIdentity.class, Duration.class));
        assertRequiresNew(method("heartbeat", RunLeaseToken.class, Duration.class));
        assertRequiresNew(method(
                "acquireTarget", RunLeaseToken.class, String.class, int.class));
    }

    private Method method(String name, Class<?>... parameterTypes) throws Exception {
        return JdbcRunLeaseStore.class.getMethod(name, parameterTypes);
    }

    private void assertRequiresNew(Method method) {
        Transactional transaction = method.getAnnotation(Transactional.class);
        assertEquals(Propagation.REQUIRES_NEW, transaction.propagation());
    }
}
