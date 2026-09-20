package tr.com.innova.akis.metadata;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tr.com.innova.akis.knowledge.MappingSql;
import tr.com.innova.akis.security.AuthorizationService;
import static tr.com.innova.akis.security.PermissionCodes.DEFINITION_READ;

/** Design-time parsing only. Supplied catalog hints never authorize database access. */
@RestController
@RequestMapping("/api/v1/projects/{projectUuid}/mapping-expressions")
final class MappingExpressionController {
    private final AuthorizationService authorization;
    MappingExpressionController(AuthorizationService authorization) { this.authorization=authorization; }

    @PostMapping("/compile")
    Result compile(@PathVariable UUID projectUuid,@Valid @RequestBody Request request) {
        authorization.requireProjectPermission(projectUuid,DEFINITION_READ);
        try {
            var sources=request.sources().stream().map(source->new MappingSql.Source(source.object(),source.alias(),source.columns())).toList();
            var ast=MappingSql.parse(request.sql(),sources,request.predicate());
            var compiled=MappingSql.render(ast,sources,request.predicate());
            return new Result(ast,compiled.references());
        } catch(IllegalArgumentException invalid) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,"MAPPING_EXPRESSION_INVALID",invalid.getMessage());
        }
    }
    record Request(@NotBlank @Size(max=16384) String sql, boolean predicate,
            @NotNull @Size(min=1,max=16) List<@Valid Source> sources) { }
    record Source(@NotBlank @Size(max=128) String object,@NotBlank @Size(max=128) String alias,
            @NotNull @Size(max=4096) Set<@NotBlank @Size(max=128) String> columns) { }
    record Result(JsonNode expression,Set<MappingSql.Reference> references) { }
}
