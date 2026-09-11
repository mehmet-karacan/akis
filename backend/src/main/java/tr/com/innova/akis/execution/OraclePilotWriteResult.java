package tr.com.innova.akis.execution;

/** Canonical target evidence observed before the caller may record and commit. */
record OraclePilotWriteResult(
        int deletedRows,
        int insertedRows,
        int verifiedRows,
        long verifiedByteCount,
        String verifiedPayloadHash) {
}
