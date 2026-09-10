package tr.com.innova.akis.metadata;

import java.util.List;

public enum DefinitionType {
    MAPPING("Mapping", "TASARIM", true, false,
            List.of("datasets", "columnMappings", "writeStrategy")),
    REUSABLE_MAPPING("Yeniden Kullanılabilir Mapping", "TASARIM", true, true,
            List.of("inputs", "outputs", "nodes")),
    PACKAGE("Paket", "TASARIM", true, false,
            List.of("firstStepId", "steps", "transitions")),
    PROCEDURE("Prosedür", "TASARIM", true, false,
            List.of("tasks")),
    VARIABLE("Değişken", "TASARIM", false, true,
            List.of("dataType", "scope", "historyMode", "valueSource")),
    SEQUENCE("Sequence", "TASARIM", false, true,
            List.of("implementation", "start", "increment", "cycle")),
    USER_FUNCTION("Kullanıcı Fonksiyonu", "TASARIM", false, true,
            List.of("returnType", "parameters", "implementations")),
    KNOWLEDGE_MODULE("Knowledge Module", "TASARIM", false, true,
            List.of("kmType", "tasks", "options")),
    LOAD_PLAN("Load Plan", "ORKESTRASYON", false, false,
            List.of("steps", "restartPolicy"));

    private final String label;
    private final String category;
    private final boolean folderRequired;
    private final boolean globalAllowed;
    private final List<String> requiredContentFields;

    DefinitionType(
            String label,
            String category,
            boolean folderRequired,
            boolean globalAllowed,
            List<String> requiredContentFields) {
        this.label = label;
        this.category = category;
        this.folderRequired = folderRequired;
        this.globalAllowed = globalAllowed;
        this.requiredContentFields = requiredContentFields;
    }

    public String label() {
        return label;
    }

    public String category() {
        return category;
    }

    public boolean folderRequired() {
        return folderRequired;
    }

    public boolean globalAllowed() {
        return globalAllowed;
    }

    public List<String> requiredContentFields() {
        return requiredContentFields;
    }
}
