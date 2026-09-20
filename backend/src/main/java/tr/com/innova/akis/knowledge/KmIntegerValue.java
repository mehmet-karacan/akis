package tr.com.innova.akis.knowledge;

import java.math.BigInteger;

/** INTEGER options may use decimal strings on the JSON wire to avoid JS rounding. */
public final class KmIntegerValue {
    private KmIntegerValue() { }

    public static boolean valid(Object value) {
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) return true;
        if (value instanceof BigInteger integer) return integer.bitLength() < 64;
        if (!(value instanceof String text) || !text.matches("[+-]?[0-9]{1,19}")) return false;
        try { Long.parseLong(text); return true; }
        catch (NumberFormatException invalid) { return false; }
    }
}
