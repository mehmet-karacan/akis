package tr.com.innova.akis.knowledge;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import tr.com.innova.akis.security.AuthorizationService;
import static tr.com.innova.akis.security.PermissionCodes.PROJECT_READ;

@RestController
@RequestMapping("/api/v1/projects/{projectUuid}/knowledge-language")
final class KnowledgeLanguageController {
    private final AuthorizationService authorization;
    KnowledgeLanguageController(AuthorizationService authorization) { this.authorization = authorization; }
    record Input(String source) { }
    record Diagnostic(boolean valid, boolean runnable, int line, String message, AkisKmLanguage.Program program) { }
    record Template(String kind, String source) { }
    @PostMapping("/validate")
    Diagnostic validate(@PathVariable UUID projectUuid, @RequestBody Input input) {
        authorization.requireProjectPermission(projectUuid, PROJECT_READ);
        try {
            return new Diagnostic(true, false, 0, "Dil doğrulandı. Fiziksel plan ve yürütme kabulü ayrıca gerekir.", AkisKmLanguage.parse(input.source()));
        } catch (AkisKmLanguage.SyntaxException invalid) {
            return new Diagnostic(false, false, invalid.line(), invalid.getMessage(), null);
        }
    }
    @GetMapping("/templates")
    List<Template> templates(@PathVariable UUID projectUuid) {
        authorization.requireProjectPermission(projectUuid, PROJECT_READ);
        return java.util.Arrays.stream(AkisKmLanguage.Kind.values()).map(kind -> new Template(kind.name(), AkisKmLanguage.example(kind))).toList();
    }
}
