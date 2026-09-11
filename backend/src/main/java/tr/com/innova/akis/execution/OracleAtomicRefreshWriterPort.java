package tr.com.innova.akis.execution;

import java.sql.Connection;

import tr.com.innova.akis.execution.RunLeasePort.TargetFenceToken;

/** Connection-bound target writer used only inside the atomic publish facade. */
interface OracleAtomicRefreshWriterPort {

    OraclePilotWriteResult write(
            Connection connection,
            PilotRuntimePlan plan,
            TargetFenceToken fenceToken,
            OraclePilotBatch batch,
            LockedTargetVerifier lockedTargetVerifier);

    @FunctionalInterface
    interface LockedTargetVerifier {

        void verify(Connection lockedTargetConnection);
    }
}
