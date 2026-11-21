package io.supportflow.customer.service.bot;

import io.supportflow.common.exception.BizException;
import io.supportflow.customer.security.RuntimeKnowledgeCommand;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Source and publication authorization share one short, model-free transaction. */
public final class AgentKnowledgeQueries {
    public static final String POLICY="visitor-published-v1";
    public record Hit(String docId,String chunkId,String title,String snippet,int scorePpm,String embeddingModel) {
        @Override public String toString(){return "KnowledgeHit[references only]";}
    }
    public record Result(String tenant,UUID eventId,UUID runId,UUID snapshotId,String sourceHash,String requestHash,
                         String queryHash,String embeddingModel,List<Hit> hits,String resourceVersion) {
        @Override public String toString(){return "KnowledgeResult[references only]";}
    }
    private final JdbcTemplate jdbc;
    private final AgentSourceSnapshots snapshots;
    public AgentKnowledgeQueries(JdbcTemplate jdbc){this.jdbc=jdbc;snapshots=new AgentSourceSnapshots(jdbc);}

    public Result read(String tenant,UUID event,UUID run,RuntimeKnowledgeCommand command) {
        var source=snapshots.read(tenant,event,run);
        if(!source.snapshotId().equals(command.snapshotId())) throw new BizException(40905,"知识来源快照不一致");
        List<Hit> hits=List.of();
        if(command.docIds()==null || !command.docIds().isEmpty()) {
            String vector=command.vectorLiteral();
            String sql="""
                SELECT c.doc_id,c.id AS chunk_id,left(d.title,200) AS title,left(c.content,1024) AS snippet,
                       1-(c.embedding <=> ?::vector) AS score,c.embedding_model
                FROM kb_chunk c JOIN kb_document d ON d.id=c.doc_id AND d.tenant_code=c.tenant_code
                WHERE c.tenant_code=? AND d.status=3 AND d.index_status=3 AND d.is_deleted=false
                  AND c.embedding_model=? AND c.embedding IS NOT NULL
                """;
            List<Object> parameters=new ArrayList<>(List.of(vector,tenant,command.embeddingModel()));
            if(command.docIds()!=null) {
                sql+=" AND c.doc_id IN("+String.join(",",Collections.nCopies(command.docIds().size(),"?"))+")";
                parameters.addAll(command.docIds());
            }
            sql+=" AND (c.embedding <=> ?::vector) BETWEEN 0 AND 2 ORDER BY c.embedding <=> ?::vector,c.doc_id,c.chunk_no,c.id LIMIT ? FOR SHARE OF c,d";
            parameters.add(vector);parameters.add(vector);parameters.add(command.topK());
            hits=jdbc.query(sql,(row,index)->{
                long doc=row.getLong("doc_id"),chunk=row.getLong("chunk_id");double score=row.getDouble("score");
                String title=row.getString("title"),snippet=row.getString("snippet");
                if(doc<1 || chunk<1 || !Double.isFinite(score) || score < -1 || score > 1 || title==null || snippet==null
                        || snippet.isBlank() || snippet.getBytes(StandardCharsets.UTF_8).length>4096)
                    throw new BizException(42205,"知识检索结果需要核验");
                return new Hit(Long.toString(doc),Long.toString(chunk),title,snippet,(int)Math.round(score*1_000_000),row.getString("embedding_model"));
            },parameters.toArray());
        }
        // Source locks prevent takeover while reading; recheck database-time TTL
        // after knowledge row locks/SQL, before releasing a successful result.
        snapshots.read(tenant,event,run);
        hits=List.copyOf(hits);
        String version=projectionHash(tenant,event,run,source.snapshotId(),source.payloadHash(),command.requestHash(),command.queryHash(),command.embeddingModel(),hits);
        return new Result(tenant,event,run,source.snapshotId(),source.payloadHash(),command.requestHash(),command.queryHash(),command.embeddingModel(),hits,version);
    }
    public static String projectionHash(String tenant,UUID event,UUID run,UUID snapshot,String sourceHash,String requestHash,String queryHash,String model,List<Hit> hits) {
        List<String> parts=new ArrayList<>(List.of("supportflow-knowledge-search:v1",POLICY,tenant,event.toString(),run.toString(),snapshot.toString(),sourceHash,requestHash,queryHash,model,Integer.toString(hits.size())));
        for(var hit:hits)parts.addAll(List.of(hit.docId(),hit.chunkId(),digest(hit.title()),digest(hit.snippet()),Integer.toString(hit.scorePpm()),hit.embeddingModel()));
        return digest(String.join("\n",parts));
    }
    private static String digest(String value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.GeneralSecurityException unavailable){throw new IllegalStateException("Knowledge digest unavailable");}
    }
}
