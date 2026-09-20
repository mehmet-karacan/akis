package tr.com.innova.akis.execution;

import java.util.*;
import tr.com.innova.akis.discovery.SchemaFingerprintInput;
import tr.com.innova.akis.knowledge.*;

/** Only typed direct columns from the already verified snapshots can become work DDL. */
record StagedColumnLayout(List<OracleWorkTableManager.Column> work,List<JdbcStagingTransfer.Column> transfer,
        JdbcWorkQualityChecks.Contract quality) {
    static StagedColumnLayout create(StagedRuntimePlan plan,SchemaFingerprintInput target) {
        List<OracleWorkTableManager.Column> work=new ArrayList<>();List<JdbcStagingTransfer.Column> transfer=new ArrayList<>();
        List<String> required=new ArrayList<>();List<List<String>> keys=new ArrayList<>();
        var byName=new HashMap<String,SchemaFingerprintInput.Column>();target.columns().forEach(c->byName.put(c.reference(),c));
        for(int mappingIndex=0;mappingIndex<plan.columnMappings().size();mappingIndex++) {
            var mapping=plan.columnMappings().get(mappingIndex);
            var column=Objects.requireNonNull(byName.get(mapping.targetColumn()));
            String type=column.producerType().toUpperCase(Locale.ROOT).replaceAll("\\(.*", "").strip();
            String ddl=switch(type) {
                case "NUMBER" -> column.precision()==null?"NUMBER":"NUMBER("+column.precision()+","+(column.scale()==null?0:column.scale())+")";
                case "VARCHAR2" -> "VARCHAR2("+column.length()+" CHAR)";
                case "NVARCHAR2" -> "NVARCHAR2("+column.length()+")";
                case "DATE" -> "DATE";
                case "TIMESTAMP" -> "TIMESTAMP("+column.timePrecision()+")";
                default -> throw new IllegalArgumentException("Çalışma kolonu tipi desteklenmiyor.");
            };
            work.add(new OracleWorkTableManager.Column(mapping.targetColumn(),ddl));
            String sourceObject = mappingIndex < plan.columnSourceObjects().size()
                    ? plan.columnSourceObjects().get(mappingIndex)
                    : plan.source() == null ? "SOURCE" : plan.source().datasetId();
            transfer.add(mapping.expression()==null
                    ?new JdbcStagingTransfer.Column(sourceObject,mapping.sourceColumn(),mapping.targetColumn(),JdbcStagingTransfer.Type.valueOf(type))
                    :new JdbcStagingTransfer.Column(null,null,mapping.targetColumn(),JdbcStagingTransfer.Type.valueOf(type),mapping.expression()));
            if(!column.nullable()) required.add(mapping.targetColumn());
        }
        var selected=new HashSet<>(work.stream().map(OracleWorkTableManager.Column::name).toList());
        for(var constraint:target.constraints()) if(constraint.enabled() && Set.of("PK","UK").contains(constraint.type())) {
            if(!selected.containsAll(constraint.columnReferences())) {
                if(plan.program().steps().stream().anyMatch(s->s.operation()==AkisKmLanguage.Operation.CHECK_UNIQUE))
                    throw new IllegalArgumentException("CKM benzersizlik kontrolü için hedef anahtarının tamamı eşlenmelidir.");
            } else keys.add(constraint.columnReferences());
        }
        return new StagedColumnLayout(List.copyOf(work),List.copyOf(transfer),new JdbcWorkQualityChecks.Contract(required,keys.stream().distinct().toList()));
    }
}
