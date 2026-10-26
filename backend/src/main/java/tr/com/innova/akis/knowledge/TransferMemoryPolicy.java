package tr.com.innova.akis.knowledge;

import tools.jackson.databind.JsonNode;

/** Managed payload/driver budget, not a process RSS or dataset-size quota. */
public final class TransferMemoryPolicy {
    public static final long DEFAULT_MIB=1024;
    public static final long MIB=1024L*1024;
    public static final String KEY="transferMemoryBudgetMiB";
    private TransferMemoryPolicy() { }
    public static long validateMiB(long value) {
        if(value<64 || value>65536) throw new IllegalArgumentException("Aktarım bellek bütçesi 64–65536 MiB aralığında olmalıdır.");
        return value;
    }
    public static long fromPolicy(JsonNode policy) {
        if(policy==null || !policy.has(KEY)) return DEFAULT_MIB;
        JsonNode value=policy.path(KEY);
        if(!value.isIntegralNumber() || !value.canConvertToLong()) throw new IllegalArgumentException("Aktarım bellek bütçesi tam sayı olmalıdır.");
        return validateMiB(value.longValue());
    }
}
