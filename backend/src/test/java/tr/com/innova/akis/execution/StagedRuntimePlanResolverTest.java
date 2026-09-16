package tr.com.innova.akis.execution;

import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import tr.com.innova.akis.knowledge.*;
import tr.com.innova.akis.projectbundle.SecretValueSanitizer;
import static org.junit.jupiter.api.Assertions.*;

class StagedRuntimePlanResolverTest {
    private final JsonMapper mapper=new JsonMapper();
    private final StagedRuntimePlanResolver resolver=new StagedRuntimePlanResolver(mapper,new SecretValueSanitizer());
    private ObjectNode scenario,manifest;
    private String scenarioHash;
    private void fixture() {
        String definition=UUID.randomUUID().toString(),version=UUID.randomUUID().toString(),environment=UUID.randomUUID().toString(),logical=UUID.randomUUID().toString();
        var content=mapper.createObjectNode();
        content.putArray("datasets").addObject().put("id","SRC").put("role","SOURCE");
        ((tools.jackson.databind.node.ArrayNode)content.path("datasets")).addObject().put("id","TGT").put("role","TARGET");
        var mapping=content.putArray("columnMappings").addObject();mapping.putObject("source").put("dataset","SRC").put("column","ID");mapping.putObject("target").put("dataset","TGT").put("column","ID");
        content.putObject("writeStrategy").put("kind","ATOMIC_DELETE_INSERT");content.putObject("staging").put("logicalSchemaUuid",logical);
        var options=new StagedMappingDefinition.Options(500,500,1201,1000000,false);content.set("options",mapper.valueToTree(options));
        var pins=content.putObject("modules");var modules=mapper.createObjectNode();
        for(var kind:List.of(AkisKmLanguage.Kind.LKM,AkisKmLanguage.Kind.IKM)) {
            String role=kind==AkisKmLanguage.Kind.LKM?"loading":"integration",id=UUID.randomUUID().toString();
            pins.putObject(role).put("versionUuid",id).put("contentHash","a".repeat(64));
            modules.putObject(role).put("versionUuid",id).put("contentHash","a".repeat(64)).put("kind",kind.name()).put("source",AkisKmLanguage.example(kind));
        }
        scenario=mapper.createObjectNode().put("compiler","AKIS").put("compilerVersion",2);
        scenario.putObject("executable").put("kind","MAPPING").set("definition",content);
        String contentHash=KmCanonical.hash(mapper,content);
        scenario.putObject("source").put("contentHash",contentHash).put("definitionType","MAPPING").put("definitionUuid",definition)
                .put("definitionVersionUuid",version).put("definitionVersion",1).put("schemaVersion",3);
        scenarioHash=KmCanonical.hash(mapper,scenario);
        manifest=mapper.createObjectNode().put("manifestVersion",2).put("runtimeCapability",StagedRuntimePlanResolver.CAPABILITY);
        manifest.putObject("definition").put("definitionUuid",definition).put("definitionVersionUuid",version).put("schemaVersion",3).put("contentHash",contentHash);
        manifest.putObject("scenario").put("planHash",scenarioHash);
        manifest.putObject("environment").put("environmentUuid",environment);
        var bindings=manifest.putArray("bindings");
        for(String role:List.of("KAYNAK","HEDEF")) {
            String owner=role.equals("KAYNAK")?"SRC":"DATA";
            var binding=bindings.addObject().put("nodeCode",role.equals("KAYNAK")?"SRC":"TGT").put("role",role).put("databaseType","ORACLE").put("dataObjectType","TABLO")
                    .put("physicalSchemaReference",owner).put("dataObjectReference","ITEMS").put("physicalIdentity",owner+".ITEMS").put("bindingVersion",1).put("schemaSnapshotFingerprint","b".repeat(64));
            for(String key:List.of("definitionDataObjectUuid","dataObjectUuid","environmentSchemaBindingUuid","physicalSchemaUuid","connectionVersionUuid","schemaSnapshotUuid")) binding.put(key,UUID.randomUUID().toString());
        }
        var physical=manifest.putObject("stagedPlan").put("planVersion",1).put("language",AkisKmLanguage.VERSION).put("scenarioPlanHash",scenarioHash)
                .put("definitionVersionUuid",version).put("environmentUuid",environment);
        physical.set("columns",content.path("columnMappings").deepCopy());physical.set("options",content.path("options").deepCopy());physical.set("modules",modules);
        var summaries=physical.putArray("bindings");
        for(var b:bindings) summaries.addObject().put("nodeCode",b.path("nodeCode").asText()).put("role",b.path("role").asText())
                .put("owner",b.path("physicalSchemaReference").asText()).put("objectName",b.path("dataObjectReference").asText())
                .put("connectionVersionUuid",b.path("connectionVersionUuid").asText()).put("physicalSchemaUuid",b.path("physicalSchemaUuid").asText())
                .put("schemaSnapshotUuid",b.path("schemaSnapshotUuid").asText()).put("schemaSnapshotFingerprint",b.path("schemaSnapshotFingerprint").asText());
        var program=AkisKmInterpreter.compile(new AkisKmInterpreter.Modules(AkisKmLanguage.example(AkisKmLanguage.Kind.LKM),null,AkisKmLanguage.example(AkisKmLanguage.Kind.IKM)));
        physical.set("steps",mapper.valueToTree(program.steps()));
        var staging=physical.putObject("staging").put("owner","WORK").put("logicalSchemaUuid",logical).put("bindingVersion",1);
        for(String key:List.of("projectUuid","physicalSchemaUuid","connectionVersionUuid","bindingUuid")) staging.put(key,UUID.randomUUID().toString());
        staging.set("prefixes",mapper.valueToTree(WorkObjectPrefixes.DEFAULTS));
        staging.set("workAreaPolicy",mapper.valueToTree(new WorkAreaPolicyService.View(new WorkAreaPolicyService.Policy(true,false,10,10000,1000000,24),1)));
        String physicalHash=KmCanonical.hash(mapper,physical);physical.put("physicalPlanHash",physicalHash);manifest.put("runtimePlanHash",physicalHash);
        sign();
    }
    private void sign() { manifest.remove("releaseHash");manifest.put("releaseHash",KmCanonical.hash(mapper,manifest)); }
    private void signPhysical() { var physical=(ObjectNode)manifest.path("stagedPlan");physical.remove("physicalPlanHash");String hash=KmCanonical.hash(mapper,physical);physical.put("physicalPlanHash",hash);manifest.put("runtimePlanHash",hash);sign(); }
    private StagedRuntimePlan resolve() { return resolver.resolve(manifest.path("releaseHash").asText(),scenarioHash,mapper.readTree(scenario.toString()),mapper.readTree(manifest.toString())); }
    @Test void resolvesRoundTrippedPlanWithoutLegacyRowLimit() {
        fixture();var plan=resolve();assertEquals(1201,plan.definition().options().maxRows());assertEquals(4,plan.program().steps().size());
    }
    @Test void rejectsTamperedPhysicalOptionsEvenIfOuterManifestIsRehashed() {
        fixture();((ObjectNode)manifest.path("stagedPlan").path("options")).put("maxRows",9999);signPhysical();
        assertThrows(IllegalArgumentException.class,this::resolve);
    }
    @Test void definitionOnlyCannotBecomeExecutableByCallingResolver() {
        fixture();manifest.put("runtimeCapability","DEFINITION_ONLY");sign();assertThrows(IllegalArgumentException.class,this::resolve);
    }
    @Test void swappedSourceAndTargetRolesAreRejected() {
        fixture();((ObjectNode)manifest.path("bindings").get(0)).put("role","HEDEF");sign();assertThrows(IllegalArgumentException.class,this::resolve);
    }
    @Test void physicalBindingsCannotDisagreeWithManifestEvenAfterRehashing() {
        fixture();((ObjectNode)manifest.path("stagedPlan").path("bindings").get(0)).put("owner","DIFFERENT");signPhysical();
        assertThrows(IllegalArgumentException.class,this::resolve);
    }
}
