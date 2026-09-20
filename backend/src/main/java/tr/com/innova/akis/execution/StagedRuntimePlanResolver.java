package tr.com.innova.akis.execution;

import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import tr.com.innova.akis.knowledge.*;
import tr.com.innova.akis.metadata.*;
import tr.com.innova.akis.projectbundle.SecretValueSanitizer;
import static tr.com.innova.akis.execution.PilotRuntimePlan.*;

/** Validates the staged capability independently of the legacy 1,000-row pilot. */
@Component
public final class StagedRuntimePlanResolver {
    public static final String CAPABILITY="ORACLE_STAGED_MAPPING_V1";
    private final ObjectMapper mapper;
    private final SecretValueSanitizer secrets;
    StagedRuntimePlanResolver(ObjectMapper mapper,SecretValueSanitizer secrets) { this.mapper=mapper; this.secrets=secrets; }
    StagedRuntimePlan resolve(String releaseHash,String scenarioHash,JsonNode scenario,JsonNode manifest) {
        try { return verify(releaseHash,scenarioHash,scenario,manifest); }
        catch(RuntimeException invalid) { throw new IllegalArgumentException("Sabitlenmiş KM yayın planı doğrulanamadı."); }
    }
    public void validatePublication(String releaseHash,String scenarioHash,JsonNode scenario,JsonNode manifest) {
        resolve(releaseHash,scenarioHash,scenario,manifest);
    }
    private StagedRuntimePlan verify(String releaseHash,String scenarioHash,JsonNode scenario,JsonNode manifest) {
        require(hash(releaseHash) && hash(scenarioHash) && scenario.isObject() && manifest.isObject());
        require(secrets.sensitivePaths(manifest).isEmpty());
        var unsigned=(ObjectNode)manifest.deepCopy(); unsigned.remove("releaseHash");
        require(releaseHash.equals(text(manifest,"releaseHash")) && releaseHash.equals(KmCanonical.hash(mapper,unsigned))
                && scenarioHash.equals(KmCanonical.hash(mapper,scenario)) && scenarioHash.equals(text(manifest.path("scenario"),"planHash"))
                && CAPABILITY.equals(text(manifest,"runtimeCapability")) && manifest.path("manifestVersion").asInt()==2);
        require("AKIS".equals(text(scenario,"compiler")) && scenario.path("compilerVersion").asInt()==2
                && "MAPPING".equals(text(scenario.path("executable"),"kind"))
                && "MAPPING".equals(text(scenario.path("source"),"definitionType")));
        var source=scenario.path("source"); var definition=manifest.path("definition");
        int mappingSchemaVersion=source.path("schemaVersion").asInt();
        require((mappingSchemaVersion==3 || mappingSchemaVersion==4) && definition.path("schemaVersion").asInt()==mappingSchemaVersion);
        UUID definitionUuid=uuid(source,"definitionUuid"),versionUuid=uuid(source,"definitionVersionUuid");
        require(definitionUuid.equals(uuid(definition,"definitionUuid")) && versionUuid.equals(uuid(definition,"definitionVersionUuid")));
        var content=scenario.path("executable").path("definition");
        require(KmCanonical.hash(mapper,content).equals(text(source,"contentHash")) && text(source,"contentHash").equals(text(definition,"contentHash")));
        new DefinitionContentValidator().validate(DefinitionType.MAPPING,mappingSchemaVersion,content);
        var semantic=StagedMappingDefinition.parse(content);
        var physical=manifest.path("stagedPlan"); require(physical.isObject());
        var unsignedPhysical=(ObjectNode)physical.deepCopy(); unsignedPhysical.remove("physicalPlanHash");
        String physicalHash=text(physical,"physicalPlanHash");
        require(hash(physicalHash) && physicalHash.equals(KmCanonical.hash(mapper,unsignedPhysical))
                && physicalHash.equals(text(manifest,"runtimePlanHash")) && physical.path("planVersion").asInt()==1
                && AkisKmLanguage.VERSION.equals(text(physical,"language")) && scenarioHash.equals(text(physical,"scenarioPlanHash"))
                && versionUuid.equals(uuid(physical,"definitionVersionUuid"))
                && uuid(physical,"environmentUuid").equals(uuid(manifest.path("environment"),"environmentUuid"))
                && content.path("columnMappings").equals(physical.path("columns"))
                && KmCanonical.hash(mapper,mapper.valueToTree(semantic.options())).equals(KmCanonical.hash(mapper,physical.path("options"))));
        var staging=physical.path("staging");
        UUID project=uuid(staging,"projectUuid"); uuid(staging,"physicalSchemaUuid"); uuid(staging,"connectionVersionUuid"); uuid(staging,"bindingUuid");
        require(staging.path("bindingVersion").asLong()>0);
        if(semantic.logicalSchemaUuid()!=null) require(semantic.logicalSchemaUuid().equals(uuid(staging,"logicalSchemaUuid")));
        StagedMappingDefinition.identifier(text(staging,"owner"));
        var prefixes=staging.path("prefixes");new WorkObjectPrefixes(text(prefixes,"loading"),text(prefixes,"integration"),text(prefixes,"error"));
        Map<String,String> programs=new HashMap<>();
        Map<String,Map<String,Object>> resolvedModuleOptions=new HashMap<>();
        require(new HashSet<>(physical.path("modules").propertyNames()).equals(semantic.modules().keySet()));
        var originalSemantic=semantic;
        originalSemantic.modules().forEach((role,pin)->{
            var module=physical.path("modules").path(role);
            String kind=switch(role) { case "loading"->"LKM";case "integration"->"IKM";case "checking"->"CKM";default->throw new IllegalArgumentException(); };
            require(pin.versionUuid().equals(uuid(module,"versionUuid")) && pin.contentHash().equals(text(module,"contentHash")) && kind.equals(text(module,"kind")));
            Map<String,Object> resolved=scalarOptions(module.path("options"));
            KnowledgeModuleRegistry.validatePinnedOptions(mapper,kind,text(module,"source"),module.path("optionSchema"),resolved);
            originalSemantic.optionsFor(role).forEach((key,value)->require(Objects.equals(value,resolved.get(key))));
            resolvedModuleOptions.put(role,resolved);
            programs.put(role,text(module,"source"));
        });
        semantic=new StagedMappingDefinition(originalSemantic.logicalSchemaUuid(),originalSemantic.modules(),resolvedModuleOptions,originalSemantic.options(),originalSemantic.sources(),originalSemantic.target(),originalSemantic.joins(),originalSemantic.filters());
        var modules=new AkisKmInterpreter.Modules(programs.get("loading"),programs.get("checking"),programs.get("integration"),resolvedModuleOptions);
        var program=AkisKmInterpreter.compile(modules);
        require(program.slots().equals(Set.of("WORK_SOURCE_1")) && mapper.valueToTree(program.steps()).equals(physical.path("steps")));
        Map<String,DatasetBinding> bindings=new HashMap<>();
        int expectedBindings=semantic.sources().isEmpty()?2:semantic.sources().size()+1;
        require(manifest.path("bindings").isArray() && manifest.path("bindings").size()==expectedBindings);
        for(var node:manifest.path("bindings")) {
            String role=text(node,"role");require(Set.of("KAYNAK","HEDEF").contains(role));
            require("ORACLE".equals(text(node,"databaseType")) && Set.of("TABLE","TABLO").contains(text(node,"dataObjectType")));
            String owner=StagedMappingDefinition.identifier(text(node,"physicalSchemaReference")),name=StagedMappingDefinition.identifier(text(node,"dataObjectReference"));
            require((owner+"."+name).equals(text(node,"physicalIdentity")) && node.path("bindingVersion").asLong()>0 && hash(text(node,"schemaSnapshotFingerprint")));
            var binding=new DatasetBinding(text(node,"nodeCode"),role.equals("KAYNAK")?DatasetRole.SOURCE:DatasetRole.TARGET,DatabaseType.ORACLE,DataObjectType.TABLE,
                    uuid(node,"definitionDataObjectUuid"),uuid(node,"dataObjectUuid"),uuid(node,"environmentSchemaBindingUuid"),uuid(node,"physicalSchemaUuid"),
                    uuid(node,"connectionVersionUuid"),uuid(node,"schemaSnapshotUuid"),node.path("bindingVersion").asLong(),text(node,"schemaSnapshotFingerprint"),text(node,"physicalIdentity"),owner,name);
            require(bindings.put(binding.datasetId(),binding)==null);
        }
        List<DatasetBinding> sourceBindings;
        DatasetBinding tgt;
        if(semantic.sources().isEmpty()) {
            sourceBindings=bindings.values().stream().filter(b->b.role()==DatasetRole.SOURCE).toList();
            tgt=bindings.values().stream().filter(b->b.role()==DatasetRole.TARGET).findFirst().orElseThrow();
        } else {
            sourceBindings=semantic.sources().stream().map(ref->Objects.requireNonNull(bindings.get(ref.id()))).toList();
            tgt=Objects.requireNonNull(bindings.get(semantic.target().id()));
        }
        require(!sourceBindings.isEmpty() && sourceBindings.stream().allMatch(b->b.role()==DatasetRole.SOURCE) && tgt.role()==DatasetRole.TARGET);
        require(sourceBindings.stream().map(DatasetBinding::connectionVersionUuid).distinct().count()==1);
        var src=sourceBindings.getFirst();
        var summaries=mapper.createArrayNode();
        bindings.values().stream().sorted(Comparator.comparing(DatasetBinding::datasetId)).forEach(b->{
            var summary=summaries.addObject().put("nodeCode",b.datasetId()).put("role",b.role()==DatasetRole.SOURCE?"KAYNAK":"HEDEF")
                    .put("owner",b.owner()).put("objectName",b.objectName()).put("connectionVersionUuid",b.connectionVersionUuid().toString())
                    .put("physicalSchemaUuid",b.physicalSchemaUuid().toString()).put("schemaSnapshotUuid",b.schemaSnapshotUuid().toString());
            summary.put("schemaSnapshotFingerprint",b.schemaSnapshotFingerprint());
        });
        require(summaries.equals(physical.path("bindings")));
        var workPolicy=mapper.treeToValue(staging.path("workAreaPolicy"),WorkAreaPolicyService.View.class);
        WorkAreaPolicyService.requireAllowed(workPolicy,semantic.options(),text(staging,"owner").equals(tgt.owner()));
        if(content.has("datasets")) for(var dataset:content.path("datasets")) require(text(dataset,"id").equals("SOURCE".equals(text(dataset,"role"))?src.datasetId():tgt.datasetId()));
        List<MappingExecutionContract.ColumnProjection> columns=new ArrayList<>();List<String> columnSourceObjects=new ArrayList<>();
        content.path("columnMappings").forEach(c->{
            if(c.hasNonNull("expression")) {
                columns.add(new MappingExecutionContract.ExpressionProjection(c.path("expression"),text(c.path("target"),"column")));
                columnSourceObjects.add(""); // Expression references are carried by the AST, not one source ID.
            } else {
                columns.add(new DirectColumnMapping(text(c.path("source"),"column"),text(c.path("target"),"column")));
                columnSourceObjects.add(text(c.path("source"),content.has("sources")?"object":"dataset"));
            }
        });
        return new StagedRuntimePlan(project,definitionUuid,versionUuid,releaseHash,physicalHash,scenarioHash,src,sourceBindings,tgt,columns,columnSourceObjects,semantic,modules,program,staging);
    }
    private static String text(JsonNode node,String key) { var value=node.path(key);require(value.isString() && !value.asText().isBlank());return value.asText(); }
    private static UUID uuid(JsonNode node,String key) { String value=text(node,key);UUID id=UUID.fromString(value);require(id.toString().equals(value));return id; }
    private static boolean hash(String value) { return value!=null && value.matches("[0-9a-f]{64}"); }
    private static Map<String,Object> scalarOptions(JsonNode node) {
        // Older physical plans predate configurable KM options.
        if(node.isMissingNode() || node.isNull()) return Map.of();
        require(node.isObject());
        Map<String,Object> values=new LinkedHashMap<>();
        node.properties().forEach(entry->{
            JsonNode value=entry.getValue();
            if(value.isBoolean()) values.put(entry.getKey(),value.booleanValue());
            else if(value.isIntegralNumber() && value.canConvertToLong()) values.put(entry.getKey(),value.longValue());
            else if(value.isTextual()) values.put(entry.getKey(),value.asText());
            else throw new IllegalArgumentException();
        });
        return Map.copyOf(values);
    }
    private static void require(boolean valid) { if(!valid) throw new IllegalArgumentException(); }
}
