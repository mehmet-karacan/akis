package tr.com.innova.akis.execution;

import java.math.BigInteger;

interface SourceSnapshotPort {
    SourceSnapshot capture();

    record SourceSnapshot(BigInteger scn, String databaseFingerprint) {
        public SourceSnapshot {
            if (scn == null || scn.signum() < 0 || databaseFingerprint == null
                    || !databaseFingerprint.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Oracle source snapshot is invalid.");
            }
        }
    }
}
