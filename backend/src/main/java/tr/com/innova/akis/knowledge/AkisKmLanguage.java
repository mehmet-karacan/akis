package tr.com.innova.akis.knowledge;

import java.util.*;
import java.util.regex.Pattern;

/** Finite declarative AKIS KM language. It never evaluates user Java or arbitrary SQL. */
public final class AkisKmLanguage {
    public static final String VERSION = "AKIS_KM/3";
    public static final String VERSION_2 = "AKIS_KM/2";
    public static final String LEGACY_VERSION = "AKIS_KM/1";
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    public enum Kind { LKM, IKM, CKM }
    public enum Site { SOURCE, STAGING, TARGET }
    public enum Operation { DROP_WORK_IF_EXISTS, CREATE_WORK, TRANSFER_JDBC, SEAL_WORK, ATOMIC_REPLACE, DROP_WORK, CHECK_NOT_NULL, CHECK_UNIQUE }
    public enum CommandChannel { SQL, SOURCE_SQL, TARGET_SQL }
    public enum OptionType { BOOLEAN, INTEGER, STRING, ENUM, SQL_HINT, IDENTIFIER, COLUMN_LIST }
    public record Step(String id, Site site, Operation operation, String slot, int line) { }
    public record Command(String stepId, CommandChannel channel, String template, int line) { }
    public record Option(String key, OptionType type, boolean required, String defaultValue, List<String> values, int line) { }
    public record Program(String language, Kind kind, List<Option> options, List<Step> steps, Map<String, String> conditions, List<Command> commands) { }
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
        String language = null;
        List<Option> options = new ArrayList<>();
        List<Step> steps = new ArrayList<>();
        List<Command> commands = new ArrayList<>();
        Map<String, String> conditions = new LinkedHashMap<>();
        Set<String> ids = new HashSet<>();
        Set<String> optionKeys = new HashSet<>();
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index].strip();
            if (line.isEmpty() || line.startsWith("--")) continue;
            int number = index + 1;
            if (!header) {
                if (!Set.of(VERSION, VERSION_2, LEGACY_VERSION).contains(line)) throw new SyntaxException(number, "İlk bildirim AKIS_KM/1, AKIS_KM/2 veya AKIS_KM/3 olmalıdır.");
                language = line;
                header = true;
                continue;
            }
            List<Token> lexical = tokens(line, number);
            String[] tokens = lexical.stream().map(Token::text).toArray(String[]::new);
            if (tokens[0].equals("MODUL")) {
                if (kind != null || tokens.length != 2 || !steps.isEmpty()) throw new SyntaxException(number, "Tek MODUL LKM/IKM/CKM bildirimi gerekir.");
                try { kind = Kind.valueOf(tokens[1]); }
                catch (IllegalArgumentException invalid) { throw new SyntaxException(number, "Modül türü LKM, IKM veya CKM olmalıdır."); }
                continue;
            }
            if (tokens[0].equals("SECENEK")) {
                if (LEGACY_VERSION.equals(language)) throw new SyntaxException(number, "SECENEK bildirimi AKIS_KM/2 veya AKIS_KM/3 gerektirir.");
                if (kind == null || !steps.isEmpty() || tokens.length != 6) throw new SyntaxException(number, "Beklenen: SECENEK ANAHTAR TIP ZORUNLU|ISTEGE_BAGLI VARSAYILAN|YOK DEGERLER|YOK");
                if (!IDENTIFIER.matcher(tokens[1]).matches() || !optionKeys.add(tokens[1])) throw new SyntaxException(number, "Seçenek anahtarı benzersiz standart kimlik olmalıdır.");
                OptionType type;
                try { type = OptionType.valueOf(tokens[2]); }
                catch (IllegalArgumentException invalid) { throw new SyntaxException(number, "Desteklenmeyen seçenek tipi."); }
                boolean required = switch (tokens[3]) { case "ZORUNLU" -> true; case "ISTEGE_BAGLI" -> false; default -> throw new SyntaxException(number, "Seçenek zorunluluğu ZORUNLU veya ISTEGE_BAGLI olmalıdır."); };
                String defaultValue = "YOK".equals(tokens[4]) && !lexical.get(4).quoted() ? null : tokens[4];
                List<String> values = "YOK".equals(tokens[5]) && !lexical.get(5).quoted() ? List.of() : List.of(tokens[5].split(",", -1));
                if (type == OptionType.ENUM && (values.isEmpty() || values.size()>64 || values.stream().anyMatch(String::isBlank) || new HashSet<>(values).size()!=values.size())) throw new SyntaxException(number, "ENUM 1-64 benzersiz, boş olmayan değer içermelidir.");
                if (type != OptionType.ENUM && !values.isEmpty()) throw new SyntaxException(number, "Değer listesi yalnız ENUM tipinde kullanılabilir.");
                if (defaultValue != null && type == OptionType.BOOLEAN && !Set.of("true", "false").contains(defaultValue)) throw new SyntaxException(number, "BOOLEAN varsayılanı true veya false olmalıdır.");
                if (defaultValue != null && type == OptionType.INTEGER) try { Long.parseLong(defaultValue); } catch (NumberFormatException invalid) { throw new SyntaxException(number, "INTEGER varsayılanı tam sayı olmalıdır."); }
                if (defaultValue != null && type == OptionType.ENUM && !values.contains(defaultValue)) throw new SyntaxException(number, "ENUM varsayılanı değer listesinde bulunmalıdır.");
                if(defaultValue!=null) {
                    boolean valid=defaultValue.length()<=1024 && switch(type) {
                        case SQL_HINT -> defaultValue.length()<=256 && defaultValue.matches("[A-Za-z0-9_$#., ()+\\-]*") && !defaultValue.contains("--");
                        case IDENTIFIER -> defaultValue.matches("[A-Za-z][A-Za-z0-9_$#]{0,127}");
                        case COLUMN_LIST -> defaultValue.matches("[A-Za-z][A-Za-z0-9_$#]*(\\s*,\\s*[A-Za-z][A-Za-z0-9_$#]*){0,63}");
                        default -> true;
                    };
                    if(!valid) throw new SyntaxException(number,"Seçeneğin varsayılan değeri tipiyle uyumsuz.");
                }
                if (options.size() >= 32) throw new SyntaxException(number, "En fazla 32 seçenek desteklenir.");
                options.add(new Option(tokens[1], type, required, defaultValue, List.copyOf(values), number));
                continue;
            }
            if (tokens[0].equals("KOMUT")) {
                if (!VERSION.equals(language)) throw new SyntaxException(number, "KOMUT bildirimi AKIS_KM/3 gerektirir.");
                if (tokens.length != 3 || !IDENTIFIER.matcher(tokens[1]).matches()) throw new SyntaxException(number, "Beklenen: KOMUT ADIM_KIMLIGI SQL|SOURCE_SQL|TARGET_SQL");
                CommandChannel channel;
                try { channel = CommandChannel.valueOf(tokens[2]); }
                catch (IllegalArgumentException invalid) { throw new SyntaxException(number, "Komut kanalı SQL, SOURCE_SQL veya TARGET_SQL olmalıdır."); }
                if (index + 1 >= lines.length || !lines[++index].strip().equals("<<<")) throw new SyntaxException(number, "KOMUT bloğu bir sonraki satırda <<< ile başlamalıdır.");
                StringBuilder template = new StringBuilder();
                boolean closed = false;
                while (++index < lines.length) {
                    if (lines[index].strip().equals(">>>")) { closed = true; break; }
                    if (template.length() > 32_768) throw new SyntaxException(number, "Komut şablonu çok büyük.");
                    if (!template.isEmpty()) template.append('\n');
                    template.append(lines[index]);
                }
                if (!closed || template.isEmpty()) throw new SyntaxException(number, "KOMUT bloğu >>> ile kapanmalı ve boş olmamalıdır.");
                try { AkisKmTemplate.validate(template.toString()); }
                catch (IllegalArgumentException invalid) { throw new SyntaxException(number, invalid.getMessage()); }
                if (commands.stream().anyMatch(command -> command.stepId().equals(tokens[1]) && command.channel() == channel)) throw new SyntaxException(number, "Aynı adım ve kanal için yalnız bir KOMUT tanımlanabilir.");
                commands.add(new Command(tokens[1], channel, template.toString(), number));
                continue;
            }
            if (!tokens[0].equals("ADIM") || (tokens.length != 5 && tokens.length != 7) || kind == null) {
                throw new SyntaxException(number, "Beklenen: ADIM KIMLIK KONUM ISLEM NESNE_SLOTU [EGER BOOLEAN_SECENEK]");
            }
            if (!IDENTIFIER.matcher(tokens[1]).matches() || !IDENTIFIER.matcher(tokens[4]).matches()) {
                throw new SyntaxException(number, "Kimlik ve slot A-Z, 0-9, alt çizgi içerebilir; harfle başlamalıdır.");
            }
            if (!ids.add(tokens[1])) throw new SyntaxException(number, "Adım kimliği tekrar edemez.");
            if (tokens.length == 7) {
                if (LEGACY_VERSION.equals(language) || !"EGER".equals(tokens[5]))
                    throw new SyntaxException(number, "Adım koşulu AKIS_KM/2 veya AKIS_KM/3 ve EGER BOOLEAN_SECENEK gerektirir.");
                Option condition = options.stream().filter(option -> option.key().equals(tokens[6])).findFirst().orElse(null);
                if (condition == null || condition.type() != OptionType.BOOLEAN)
                    throw new SyntaxException(number, "EGER koşulu bu modülde tanımlı bir BOOLEAN seçeneği olmalıdır.");
                conditions.put(tokens[1], condition.key());
            }
            if (steps.size() >= 100) throw new SyntaxException(number, "En fazla 100 adım desteklenir.");
            Site site;
            Operation operation;
            try { site = Site.valueOf(tokens[2]); operation = Operation.valueOf(tokens[3]); }
            catch (IllegalArgumentException invalid) { throw new SyntaxException(number, "Desteklenmeyen konum veya işlem."); }
            Site requiredSite = operation == Operation.ATOMIC_REPLACE ? Site.TARGET : Site.STAGING;
            boolean validKind = switch (kind) {
                case LKM -> Set.of(Operation.DROP_WORK_IF_EXISTS, Operation.CREATE_WORK, Operation.TRANSFER_JDBC, Operation.SEAL_WORK).contains(operation);
                case IKM -> Set.of(Operation.ATOMIC_REPLACE, Operation.DROP_WORK).contains(operation);
                case CKM -> Set.of(Operation.CHECK_NOT_NULL, Operation.CHECK_UNIQUE).contains(operation);
            };
            if (!validKind || site != requiredSite) throw new SyntaxException(number, "İşlem, modül türü veya konumla uyumsuz.");
            steps.add(new Step(tokens[1], site, operation, tokens[4], number));
        }
        if (!header || kind == null || steps.isEmpty()) throw new SyntaxException(1, "Dil, modül ve en az bir adım zorunludur.");
        if (VERSION.equals(language)) {
            Set<String> stepIds = steps.stream().map(Step::id).collect(java.util.stream.Collectors.toSet());
            for (Command command : commands) if (!stepIds.contains(command.stepId())) throw new SyntaxException(command.line(), "KOMUT bilinmeyen bir adıma bağlı: " + command.stepId());
            for (Step step : steps) if (commands.stream().noneMatch(command -> command.stepId().equals(step.id()))) throw new SyntaxException(step.line(), "AKIS_KM/3 adımında en az bir KOMUT zorunludur: " + step.id());
        } else if (!commands.isEmpty()) throw new SyntaxException(1, "KOMUT yalnız AKIS_KM/3 içinde kullanılabilir.");
        validateSequence(kind, steps);
        return new Program(language, kind, List.copyOf(options), List.copyOf(steps), Map.copyOf(conditions), List.copyOf(commands));
    }

    private record Token(String text, boolean quoted) { }
    private static List<Token> tokens(String line,int number) {
        List<Token> result=new ArrayList<>();
        for(int index=0;index<line.length();) {
            if(Character.isWhitespace(line.charAt(index))) { index++; continue; }
            int start=index;
            if(line.charAt(index)=='"') {
                index++; boolean closed=false;
                while(index<line.length()) {
                    char character=line.charAt(index++);
                    if(character=='\\') { if(index<line.length()) index++; }
                    else if(character=='"') { closed=true; break; }
                }
                if(!closed || (index<line.length() && !Character.isWhitespace(line.charAt(index)))) throw new SyntaxException(number,"Çift tırnaklı seçenek değeri tamamlanmadı.");
                try { result.add(new Token(new tools.jackson.databind.json.JsonMapper().readValue(line.substring(start,index),String.class),true)); }
                catch(RuntimeException invalid) { throw new SyntaxException(number,"Geçersiz çift tırnaklı seçenek değeri."); }
            } else {
                while(index<line.length() && !Character.isWhitespace(line.charAt(index))) index++;
                result.add(new Token(line.substring(start,index),false));
            }
        }
        return result;
    }

    static void validateSequence(Kind kind, List<Step> steps) {
        if (kind != Kind.CKM && steps.isEmpty()) throw new SyntaxException(1, "Yükleme ve hedef uygulama adımları koşullarla tamamen kapatılamaz.");
        if (kind == Kind.LKM) {
            Map<String, Integer> states = new HashMap<>();
            for (Step step : steps) {
                if (step.operation() == Operation.DROP_WORK_IF_EXISTS) {
                    if (states.containsKey(step.slot())) throw new SyntaxException(step.line(), "DROP_WORK_IF_EXISTS slotun ilk adımı olmalıdır.");
                    states.put(step.slot(), 0);
                    continue;
                }
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
        } else if (kind == Kind.IKM) {
            if (steps.isEmpty() || steps.getFirst().operation() != Operation.ATOMIC_REPLACE
                    || steps.size() > 2
                    || steps.size() == 2 && steps.get(1).operation() != Operation.DROP_WORK
                    || steps.size() == 2 && !steps.getFirst().slot().equals(steps.get(1).slot()))
                throw new SyntaxException(steps.getLast().line(), "IKM sırası ATOMIC_REPLACE → DROP_WORK olmalıdır.");
        }
    }

    public static String example(Kind kind) {
        return switch (kind) {
            case LKM -> "AKIS_KM/3\nMODUL LKM\nSECENEK DISTINCT BOOLEAN ISTEGE_BAGLI false YOK\nSECENEK ORACLE_HINT SQL_HINT ISTEGE_BAGLI YOK YOK\nADIM ONCEKI_CALISMAYI_TEMIZLE STAGING DROP_WORK_IF_EXISTS WORK_SOURCE_1\nKOMUT ONCEKI_CALISMAYI_TEMIZLE SQL\n<<<\ndrop table {{ akisRef.table(\"WORK\", \"QUALIFIED\") }}\n>>>\nADIM HAZIRLA STAGING CREATE_WORK WORK_SOURCE_1\nKOMUT HAZIRLA SQL\n<<<\ncreate table {{ akisRef.table(\"WORK\", \"QUALIFIED\") }} (\n{{ akisRef.columns(\"TARGET\", \"DDL\", \",\\n\") }}\n)\n>>>\nADIM AKTAR STAGING TRANSFER_JDBC WORK_SOURCE_1\nKOMUT AKTAR SOURCE_SQL\n<<<\n{{ akisRef.context(\"SOURCE_SELECT\") }}\n>>>\nKOMUT AKTAR TARGET_SQL\n<<<\n{{ akisRef.context(\"STAGING_INSERT\") }}\n>>>\nADIM MUHURLE STAGING SEAL_WORK WORK_SOURCE_1\nKOMUT MUHURLE SQL\n<<<\nselect count(*) from {{ akisRef.table(\"WORK\", \"QUALIFIED\") }}\n>>>\n";
            case IKM -> "AKIS_KM/3\nMODUL IKM\nSECENEK WRITE_MODE ENUM ZORUNLU ATOMIC_DELETE_INSERT APPEND,MERGE,TRUNCATE_LOAD,ATOMIC_DELETE_INSERT\nSECENEK KEY_COLUMNS COLUMN_LIST ISTEGE_BAGLI YOK YOK\nSECENEK TRUNCATE_TARGET BOOLEAN ISTEGE_BAGLI false YOK\nSECENEK DROP_WORK_TABLE BOOLEAN ISTEGE_BAGLI true YOK\nSECENEK ORACLE_HINT SQL_HINT ISTEGE_BAGLI YOK YOK\nADIM HEDEFE_YAZ TARGET ATOMIC_REPLACE WORK_SOURCE_1\nKOMUT HEDEFE_YAZ SQL\n<<<\n{{ akisRef.integration(\"WRITE_MODE\") }}\n>>>\nADIM DROP_WORK_TABLE STAGING DROP_WORK WORK_SOURCE_1 EGER DROP_WORK_TABLE\nKOMUT DROP_WORK_TABLE SQL\n<<<\ndrop table {{ akisRef.table(\"WORK\", \"QUALIFIED\") }}\n>>>\n";
            case CKM -> "AKIS_KM/3\nMODUL CKM\nADIM BOSLUK_KONTROL STAGING CHECK_NOT_NULL WORK_SOURCE_1\nKOMUT BOSLUK_KONTROL SQL\n<<<\n{{ akisRef.check(\"NOT_NULL\") }}\n>>>\n";
        };
    }
}
