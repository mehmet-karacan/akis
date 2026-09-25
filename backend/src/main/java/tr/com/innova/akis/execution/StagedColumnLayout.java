package tr.com.innova.akis.execution;

import java.util.*;
import tr.com.innova.akis.discovery.SchemaFingerprintInput;
import tr.com.innova.akis.knowledge.*;

/** Only typed direct columns from the already verified snapshots can become work DDL. */
record StagedColumnLayout(List<WorkTableManagerPort.Column> work,List<JdbcStagingTransfer.Column> transfer,
        JdbcWorkQualityChecks.Contract quality) {
    static StagedColumnLayout create(StagedRuntimePlan plan,SchemaFingerprintInput target) {
        return create(plan,target,PilotRuntimePlan.DatabaseType.ORACLE,Map.of());
    }

    /**
     * The work table mirrors the TARGET columns, so its DDL is written in the target technology; the transfer reads the
     * SOURCE cursor, so its JDBC type comes from the source column. For Oracle→Oracle the two coincide (the preflight
     * requires the same producer type on both sides), so that path keeps deriving both from the target.
     */
    static StagedColumnLayout create(StagedRuntimePlan plan,SchemaFingerprintInput target,PilotRuntimePlan.DatabaseType technology,
            Map<String,SchemaFingerprintInput> sourceSnapshots) {
        if(technology==PilotRuntimePlan.DatabaseType.POSTGRESQL) return postgres(plan,target,sourceSnapshots);
        List<WorkTableManagerPort.Column> work=new ArrayList<>();List<JdbcStagingTransfer.Column> transfer=new ArrayList<>();
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
            work.add(new WorkTableManagerPort.Column(mapping.targetColumn(),ddl));
            String sourceObject = mappingIndex < plan.columnSourceObjects().size()
                    ? plan.columnSourceObjects().get(mappingIndex)
                    : plan.source() == null ? "SOURCE" : plan.source().datasetId();
            transfer.add(mapping.expression()==null
                    ?new JdbcStagingTransfer.Column(sourceObject,mapping.sourceColumn(),mapping.targetColumn(),JdbcStagingTransfer.Type.valueOf(type))
                    :new JdbcStagingTransfer.Column(null,null,mapping.targetColumn(),JdbcStagingTransfer.Type.valueOf(type),mapping.expression()));
            if(!column.nullable()) required.add(mapping.targetColumn());
        }
        var selected=new HashSet<>(work.stream().map(WorkTableManagerPort.Column::name).toList());
        for(var constraint:target.constraints()) if(constraint.enabled() && Set.of("PK","UK").contains(constraint.type())) {
            if(!selected.containsAll(constraint.columnReferences())) {
                if(plan.program().steps().stream().anyMatch(s->s.operation()==AkisKmLanguage.Operation.CHECK_UNIQUE))
                    throw new IllegalArgumentException("CKM benzersizlik kontrolü için hedef anahtarının tamamı eşlenmelidir.");
            } else keys.add(constraint.columnReferences());
        }
        return new StagedColumnLayout(List.copyOf(work),List.copyOf(transfer),new JdbcWorkQualityChecks.Contract(required,keys.stream().distinct().toList()));
    }

    private static StagedColumnLayout postgres(StagedRuntimePlan plan,SchemaFingerprintInput target,Map<String,SchemaFingerprintInput> sourceSnapshots) {
        List<WorkTableManagerPort.Column> work=new ArrayList<>();List<JdbcStagingTransfer.Column> transfer=new ArrayList<>();
        List<String> required=new ArrayList<>();List<List<String>> keys=new ArrayList<>();
        var byName=new HashMap<String,SchemaFingerprintInput.Column>();target.columns().forEach(c->byName.put(c.reference(),c));
        var sourceColumns=new HashMap<String,Map<String,SchemaFingerprintInput.Column>>();
        sourceSnapshots.forEach((id,snapshot)->{
            var columns=new HashMap<String,SchemaFingerprintInput.Column>();
            snapshot.columns().forEach(c->columns.put(c.reference(),c));
            sourceColumns.put(id,columns);
        });
        for(int mappingIndex=0;mappingIndex<plan.columnMappings().size();mappingIndex++) {
            var mapping=plan.columnMappings().get(mappingIndex);
            var column=Objects.requireNonNull(byName.get(mapping.targetColumn()));
            String ddl=tr.com.innova.akis.knowledge.PostgresWorkStructure.ddlOf(column.producerType());
            if(!tr.com.innova.akis.knowledge.PostgresWorkStructure.supported(ddl)) throw new IllegalArgumentException("Çalışma kolonu tipi desteklenmiyor.");
            work.add(new WorkTableManagerPort.Column(mapping.targetColumn(),ddl));
            String sourceObject=mappingIndex<plan.columnSourceObjects().size()
                    ? plan.columnSourceObjects().get(mappingIndex)
                    : plan.source()==null?"SOURCE":plan.source().datasetId();
            // The cursor is Oracle: read with the source column's own producer type (expressions carry the target's canonical type).
            var source=mapping.expression()!=null?null:sourceColumns.getOrDefault(sourceObject,Map.of()).get(mapping.sourceColumn());
            var readType=readType(source==null?column:source);
            transfer.add(mapping.expression()==null
                    ?new JdbcStagingTransfer.Column(sourceObject,mapping.sourceColumn(),mapping.targetColumn(),readType)
                    :new JdbcStagingTransfer.Column(null,null,mapping.targetColumn(),readType,mapping.expression()));
            if(!column.nullable()) required.add(mapping.targetColumn());
        }
        var selected=new HashSet<>(work.stream().map(WorkTableManagerPort.Column::name).toList());
        for(var constraint:target.constraints()) if(constraint.enabled() && Set.of("PK","UK").contains(constraint.type())) {
            if(!selected.containsAll(constraint.columnReferences())) {
                if(plan.program().steps().stream().anyMatch(s->s.operation()==AkisKmLanguage.Operation.CHECK_UNIQUE))
                    throw new IllegalArgumentException("CKM benzersizlik kontrolü için hedef anahtarının tamamı eşlenmelidir.");
            } else keys.add(constraint.columnReferences());
        }
        return new StagedColumnLayout(List.copyOf(work),List.copyOf(transfer),new JdbcWorkQualityChecks.Contract(required,keys.stream().distinct().toList()));
    }

    /** Oracle producer type of the column the cursor returns, as a transfer read type. */
    private static JdbcStagingTransfer.Type readType(SchemaFingerprintInput.Column column) {
        String type=column.producerType().toUpperCase(Locale.ROOT).replaceAll("\\(.*","").strip();
        return switch(type) {
            case "NUMBER" -> JdbcStagingTransfer.Type.NUMBER;
            case "VARCHAR2" -> JdbcStagingTransfer.Type.VARCHAR2;
            case "NVARCHAR2" -> JdbcStagingTransfer.Type.NVARCHAR2;
            case "DATE" -> JdbcStagingTransfer.Type.DATE;
            case "TIMESTAMP" -> JdbcStagingTransfer.Type.TIMESTAMP;
            case "CLOB" -> JdbcStagingTransfer.Type.CLOB;
            case "BLOB" -> JdbcStagingTransfer.Type.BLOB;
            case "FLOAT", "BINARY_FLOAT", "BINARY_DOUBLE" -> JdbcStagingTransfer.Type.FLOAT;
            default -> throw new IllegalArgumentException("Kaynak kolon tipi desteklenmiyor.");
        };
    }
}
