package io.supportflow.customer.security;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Parsed only after service authentication; contains no model credential or SQL. */
public record RuntimeKnowledgeCommand(UUID snapshotId,String queryHash,String embeddingModel,List<Double> vector,
                                      int topK,List<Long> docIds,String requestHash) {
    public static final String ATTRIBUTE="supportflowRuntimeKnowledgeCommand";
    public RuntimeKnowledgeCommand {
        if(snapshotId==null || queryHash==null || !queryHash.matches("[0-9a-f]{64}") || requestHash==null || !requestHash.matches("[0-9a-f]{64}")
                || embeddingModel==null || !embeddingModel.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,31}:[A-Za-z0-9][A-Za-z0-9_.:-]{0,94}")
                || topK<1 || topK>5 || vector==null || vector.size()!=1024) throw new IllegalArgumentException("Invalid knowledge query");
        boolean nonzero=false;
        for(Double value:vector) {
            if(value==null || !Double.isFinite(value) || Math.abs(value)>1e6) throw new IllegalArgumentException("Invalid query vector");
            if(Double.parseDouble(String.format(Locale.ROOT,"%.7f",value))!=0) nonzero=true;
        }
        if(!nonzero) throw new IllegalArgumentException("Invalid query vector");
        vector=List.copyOf(vector);
        if(docIds!=null) {
            if(docIds.size()>200 || docIds.stream().anyMatch(id->id==null || id<1)) throw new IllegalArgumentException("Invalid document scope");
            docIds=docIds.stream().distinct().toList();
        }
    }
    public static RuntimeKnowledgeCommand parse(JsonNode object,String requestHash) {
        if(object==null || !object.isObject()) throw new IllegalArgumentException("Invalid knowledge object");
        Set<String> fields=new HashSet<>();object.fieldNames().forEachRemaining(fields::add);
        if(!object.isObject() || !fields.equals(Set.of("snapshot_id","query_sha256","embedding_model","vector","top_k","doc_ids")))
            throw new IllegalArgumentException("Invalid knowledge fields");
        for(String key:List.of("snapshot_id","query_sha256","embedding_model")) if(!object.path(key).isTextual()) throw new IllegalArgumentException("Invalid knowledge reference");
        UUID snapshot=UUID.fromString(object.path("snapshot_id").textValue());
        if(!snapshot.toString().equals(object.path("snapshot_id").textValue())) throw new IllegalArgumentException("Canonical snapshot required");
        if(!object.path("top_k").isIntegralNumber() || !object.path("top_k").canConvertToInt()) throw new IllegalArgumentException("Invalid result limit");
        var values=object.path("vector");
        if(!values.isArray() || values.size()!=1024) throw new IllegalArgumentException("Invalid vector size");
        List<Double> vector=new ArrayList<>();
        for(var item:values) { if(!item.isNumber()) throw new IllegalArgumentException("Invalid vector number");vector.add(item.doubleValue()); }
        List<Long> ids=null;var scope=object.path("doc_ids");
        if(!scope.isNull()) {
            if(!scope.isArray() || scope.size()>200) throw new IllegalArgumentException("Invalid document scope");
            ids=new ArrayList<>();
            for(var item:scope) { if(!item.isIntegralNumber() || !item.canConvertToLong()) throw new IllegalArgumentException("Invalid document identifier");ids.add(item.longValue()); }
        }
        return new RuntimeKnowledgeCommand(snapshot,object.path("query_sha256").textValue(),object.path("embedding_model").textValue(),
                vector,object.path("top_k").intValue(),ids,requestHash);
    }
    public String vectorLiteral() { return "["+String.join(",",vector.stream().map(String::valueOf).toList())+"]"; }
    @Override public String toString(){return "RuntimeKnowledgeCommand[references only]";}
}
