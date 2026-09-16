package tr.com.innova.akis.knowledge;

import java.util.*;
import java.util.regex.Pattern;

/** AKIS_KM/1: deliberately finite declarative language, not Java/SQL eval. */
public final class AkisKmLanguage {
    public static final String VERSION = "AKIS_KM/1";
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    public enum Kind { LKM, IKM, CKM }
    public enum Site { SOURCE, STAGING, TARGET }
    public enum Operation { CREATE_WORK, TRANSFER_JDBC, SEAL_WORK, ATOMIC_REPLACE, CHECK_NOT_NULL, CHECK_UNIQUE }
    public record Step(String id, Site site, Operation operation, String slot, int line) { }
    public record Program(String language, Kind kind, List<Step> steps) { }
    public static final class SyntaxException extends IllegalArgumentException {
        private final int line;
        public SyntaxException(int line, String message) { super("Satır " + line + ": " + message); this.line = line; }
        public int line() { return line; }
    }

    private AkisKmLanguage() { }

    public static Program parse(String source) {
        if (source == null || source.length() > 65_536) throw new SyntaxException(1, "KM metni boş veya çok büyük.");
        String[] lines = source.split("\\R", -1);
        boolean header = false;
        Kind kind = null;
        List<Step> steps = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index].strip();
            if (line.isEmpty() || line.startsWith("--")) continue;
            int number = index + 1;
            if (!header) {
                if (!line.equals(VERSION)) throw new SyntaxException(number, "İlk bildirim AKIS_KM/1 olmalıdır.");
                header = true;
                continue;
            }
            String[] tokens = line.split("\\s+");
            if (tokens[0].equals("MODUL")) {
                if (kind != null || tokens.length != 2 || !steps.isEmpty()) throw new SyntaxException(number, "Tek MODUL LKM/IKM/CKM bildirimi gerekir.");
                try { kind = Kind.valueOf(tokens[1]); }
                catch (IllegalArgumentException invalid) { throw new SyntaxException(number, "Modül türü LKM, IKM veya CKM olmalıdır."); }
                continue;
            }
            if (!tokens[0].equals("ADIM") || tokens.length != 5 || kind == null) {
                throw new SyntaxException(number, "Beklenen: ADIM KIMLIK KONUM ISLEM NESNE_SLOTU");
            }
            if (!IDENTIFIER.matcher(tokens[1]).matches() || !IDENTIFIER.matcher(tokens[4]).matches()) {
                throw new SyntaxException(number, "Kimlik ve slot A-Z, 0-9, alt çizgi içerebilir; harfle başlamalıdır.");
            }
            if (!ids.add(tokens[1])) throw new SyntaxException(number, "Adım kimliği tekrar edemez.");
            if (steps.size() >= 100) throw new SyntaxException(number, "En fazla 100 adım desteklenir.");
            Site site;
            Operation operation;
            try { site = Site.valueOf(tokens[2]); operation = Operation.valueOf(tokens[3]); }
            catch (IllegalArgumentException invalid) { throw new SyntaxException(number, "Desteklenmeyen konum veya işlem."); }
            Site requiredSite = operation == Operation.ATOMIC_REPLACE ? Site.TARGET : Site.STAGING;
            boolean validKind = switch (kind) {
                case LKM -> Set.of(Operation.CREATE_WORK, Operation.TRANSFER_JDBC, Operation.SEAL_WORK).contains(operation);
                case IKM -> operation == Operation.ATOMIC_REPLACE;
                case CKM -> Set.of(Operation.CHECK_NOT_NULL, Operation.CHECK_UNIQUE).contains(operation);
            };
            if (!validKind || site != requiredSite) throw new SyntaxException(number, "İşlem, modül türü veya konumla uyumsuz.");
            steps.add(new Step(tokens[1], site, operation, tokens[4], number));
        }
        if (!header || kind == null || steps.isEmpty()) throw new SyntaxException(1, "Dil, modül ve en az bir adım zorunludur.");
        validateSequence(kind, steps);
        return new Program(VERSION, kind, List.copyOf(steps));
    }

    private static void validateSequence(Kind kind, List<Step> steps) {
        if (kind == Kind.LKM) {
            Map<String, Integer> states = new HashMap<>();
            for (Step step : steps) {
                int expected = switch (step.operation()) {
                    case CREATE_WORK -> 0;
                    case TRANSFER_JDBC -> 1;
                    case SEAL_WORK -> 2;
                    default -> throw new SyntaxException(step.line(), "Geçersiz LKM işlemi.");
                };
                if (states.getOrDefault(step.slot(), 0) != expected) throw new SyntaxException(step.line(), "Slot sırası CREATE_WORK → TRANSFER_JDBC → SEAL_WORK olmalıdır.");
                states.put(step.slot(), expected + 1);
            }
            if (states.values().stream().anyMatch(state -> state != 3)) throw new SyntaxException(steps.getLast().line(), "Her yükleme slotu mühürlenmelidir.");
        } else if (kind == Kind.IKM && steps.size() != 1) {
            throw new SyntaxException(steps.getLast().line(), "İlk IKM sözleşmesi tek atomik hedef uygulaması destekler.");
        }
    }

    public static String example(Kind kind) {
        return switch (kind) {
            case LKM -> "AKIS_KM/1\nMODUL LKM\nADIM HAZIRLA STAGING CREATE_WORK WORK_SOURCE_1\nADIM AKTAR STAGING TRANSFER_JDBC WORK_SOURCE_1\nADIM MUHURLE STAGING SEAL_WORK WORK_SOURCE_1\n";
            case IKM -> "AKIS_KM/1\nMODUL IKM\nADIM HEDEFE_YAZ TARGET ATOMIC_REPLACE WORK_SOURCE_1\n";
            case CKM -> "AKIS_KM/1\nMODUL CKM\nADIM BOSLUK_KONTROL STAGING CHECK_NOT_NULL WORK_SOURCE_1\n";
        };
    }
}
