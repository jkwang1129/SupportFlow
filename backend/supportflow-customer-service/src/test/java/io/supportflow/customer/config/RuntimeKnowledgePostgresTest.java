package io.supportflow.customer.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.supportflow.common.auth.CustomerInternalCredential;
import io.supportflow.common.exception.BizException;
import io.supportflow.common.exception.GlobalExceptionHandler;
import io.supportflow.common.readiness.SchemaReadiness;
import io.supportflow.common.readiness.SchemaReadinessFilter;
import io.supportflow.customer.controller.internal.RuntimeKnowledgeController;
import io.supportflow.customer.security.CustomerInternalAccess;
import io.supportflow.customer.security.RuntimeKnowledgeCommand;
import io.supportflow.customer.security.RuntimeSourceAccessFilter;
import io.supportflow.customer.service.bot.AgentEventOutbox;
import io.supportflow.customer.service.bot.AgentKnowledgeQueries;
import io.supportflow.customer.service.bot.AgentSourceSnapshots;
import io.supportflow.customer.testkit.RuntimeSourceWire;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

@EnabledIfEnvironmentVariable(named="SUPPORTFLOW_RLS_TEST_URL",matches=".+")
class RuntimeKnowledgePostgresTest extends SessionPostgresFixture {
    private static final ObjectMapper JSON=new ObjectMapper();
    private AgentEventOutbox.Event event;
    private AgentSourceSnapshots.Snapshot snapshot;
    private UUID run;
    private String path;
    private MockMvc mvc;
    private final Map<String,Long> docs=new LinkedHashMap<>();
    private <T>T tx(Supplier<T> work){return scoped(()->transaction.execute(status->work.get()));}
    private static String vector(boolean zero){return "["+(zero?"0":"0.25")+",0".repeat(1023)+"]";}
    private void admin(String sql){try(var db=DriverManager.getConnection(System.getenv("SUPPORTFLOW_RLS_TEST_URL"),"supportflow",System.getenv("SUPPORTFLOW_RLS_TEST_MIGRATION_PASSWORD"));var statement=db.createStatement()){statement.execute(sql);}catch(Exception failure){throw new IllegalStateException("Disposable fixture administration failed",failure);}}
    @BeforeEach void prepareKnowledge() {
        var outbox=new AgentEventOutbox(jdbc);run=UUID.randomUUID();
        event=tx(()->{
            var message=service.appendMessage("TENANT_A",sessionNo,1,null,1,"PRIVATE-SOURCE",1,"knowledge-source");
            var value=outbox.enqueue("TENANT_A",Long.parseLong(message.msgId()),"runtime-v1",500);
            var lease=outbox.claim("TENANT_A","knowledge-api-test",30);outbox.acknowledge(lease,value.eventId(),run);return value;
        });
        snapshot=tx(()->new AgentSourceSnapshots(jdbc).capture("TENANT_A",event.eventId(),run));
        for(String label:List.of("ready","ready2","draft","review","offline","unindexed","indexing","failed","deleted","foreign","local","zero")){
            long id=IDS.incrementAndGet();docs.put(label,id);
            String tenant=label.equals("foreign")?"TENANT_B":"TENANT_A";
            int status=switch(label){case "draft"->1;case "review"->2;case "offline"->4;default->3;};
            int index=switch(label){case "unindexed"->1;case "indexing"->2;case "failed"->4;default->3;};
            String content=label.equals("ready2")?"🙂".repeat(1100)+"PRIVATE-TAIL":label.startsWith("ready")?"PUBLIC-KNOWLEDGE":"PRIVATE-"+label;
            try(var db=DriverManager.getConnection(System.getenv("SUPPORTFLOW_RLS_TEST_URL"),"supportflow",System.getenv("SUPPORTFLOW_RLS_TEST_MIGRATION_PASSWORD"))){
                try(var insert=db.prepareStatement("INSERT INTO kb_document(id,tenant_code,doc_no,title,status,index_status,is_deleted) VALUES(?,?,?,'Public title',?,?,?)")){
                    insert.setLong(1,id);insert.setString(2,tenant);insert.setString(3,"KNOW_"+id);insert.setInt(4,status);insert.setInt(5,index);insert.setBoolean(6,label.equals("deleted"));insert.executeUpdate();}
                try(var insert=db.prepareStatement("INSERT INTO kb_chunk(id,tenant_code,doc_id,chunk_no,content,embedding,embedding_model) VALUES(?,?,?,0,?,?::vector,?)")){
                    insert.setLong(1,id);insert.setString(2,tenant);insert.setLong(3,id);insert.setString(4,content);insert.setString(5,vector(label.equals("zero")));
                    insert.setString(6,label.equals("local")?"local-hash":"fixture:fixture-embedding");insert.executeUpdate();}
            }catch(Exception failure){throw new IllegalStateException("Synthetic knowledge corpus failed",failure);}
        }
        path="/api/customer/internal/runtime/events/"+event.eventId()+"/runs/"+run+"/knowledge/search";
        var registry=new InterceptorRegistry(){HandlerInterceptor[] values(){return getInterceptors().stream().map(HandlerInterceptor.class::cast).toArray(HandlerInterceptor[]::new);}};
        new CustomerInternalAccess("","","").addInterceptors(registry);
        // Isolate the query handler from global KB readiness to exercise actual
        // SELECT revocation as its own sanitized persistence failure below.
        var readiness=new SchemaReadiness(jdbc.getDataSource(),Map.of("agent_source_snapshot","tenant_code,event_id,payload_hash,payload_json"));
        mvc=MockMvcBuilders.standaloneSetup(new RuntimeKnowledgeController(jdbc,transaction.getTransactionManager()))
                .setControllerAdvice(new GlobalExceptionHandler()).addFilters(new RuntimeSourceAccessFilter(RuntimeSourceWire.KEY,false,false,true,"","",""),new SchemaReadinessFilter(readiness))
                .addInterceptors(registry.values()).build();
    }
    private ObjectNode input(){
        var value=JSON.createObjectNode();value.put("snapshot_id",snapshot.snapshotId().toString());value.put("query_sha256","a".repeat(64));
        value.put("embedding_model","fixture:fixture-embedding");value.put("top_k",5);value.putNull("doc_ids");var vector=value.putArray("vector");vector.add(.25);for(int i=1;i<1024;i++)vector.add(0.0);return value;
    }
    private MvcResult post(ObjectNode input,String tenant)throws Exception{
        byte[] raw=JSON.writeValueAsBytes(input);
        return mvc.perform(request(HttpMethod.POST,URI.create(path)).contentType("application/json").content(raw).header("X-Tenant-Code",tenant)
                .header(CustomerInternalCredential.HEADER,RuntimeSourceWire.credential("POST",path,tenant,raw,Instant.now().getEpochSecond()+20))).andReturn();
    }
    private JsonNode root(MvcResult result)throws Exception{return JSON.readTree(result.getResponse().getContentAsString());}
    private JsonNode success(ObjectNode input)throws Exception{var result=post(input,"TENANT_A");assertEquals(200,result.getResponse().getStatus(),result.getResponse().getContentAsString());return root(result).path("data");}
    private void denied(int status,int code)throws Exception{var result=post(input(),"TENANT_A");assertEquals(status,result.getResponse().getStatus());assertEquals(code,root(result).path("code").intValue());assertTrue(root(result).path("data").isNull());assertFalse(result.getResponse().getContentAsString().contains("PRIVATE"));}
    private RuntimeKnowledgeCommand command(){try{byte[] raw=JSON.writeValueAsBytes(input());return RuntimeKnowledgeCommand.parse(input(),digest(raw));}catch(Exception failure){throw new IllegalStateException(failure);}}
    @Override @AfterEach void cleanup(){
        try{if(!docs.isEmpty()) {String ids=String.join(",",docs.values().stream().map(String::valueOf).toList());admin("DELETE FROM kb_chunk WHERE id IN("+ids+"); DELETE FROM kb_document WHERE id IN("+ids+")");}
            if(event!=null)admin("DELETE FROM agent_source_snapshot WHERE event_id='"+event.eventId()+"'; DELETE FROM agent_event_outbox WHERE event_id='"+event.eventId()+"'");
        }finally{super.cleanup();}
    }
    @Test void returnsOnlyPublishedCompatibleFactsWithBoundedSnippetAndProjectionBinding()throws Exception{
        var input=input();var data=success(input);assertEquals(12,data.size());assertEquals("knowledge-search-v1",data.path("schema_version").textValue());
        assertEquals("visitor-published-v1",data.path("policy_version").textValue());assertEquals(2,data.path("hits").size());
        assertFalse(data.toString().contains("PRIVATE"));assertEquals(digest(JSON.writeValueAsBytes(input)),data.path("request_sha256").textValue());
        var ids=new HashSet<String>();for(var hit:data.path("hits")){assertEquals(6,hit.size());ids.add(hit.path("doc_id").textValue());assertEquals(1_000_000,hit.path("score_ppm").intValue());
            if(hit.path("doc_id").textValue().equals(docs.get("ready2").toString()))assertEquals(4096,hit.path("snippet").textValue().getBytes(StandardCharsets.UTF_8).length);}
        assertEquals(Set.of(docs.get("ready").toString(),docs.get("ready2").toString()),ids);
        var canonical=new ArrayList<>(List.of("supportflow-knowledge-search:v1","visitor-published-v1","TENANT_A",event.eventId().toString(),run.toString(),snapshot.snapshotId().toString(),snapshot.payloadHash(),data.path("request_sha256").textValue(),"a".repeat(64),"fixture:fixture-embedding","2"));
        for(var hit:data.path("hits"))canonical.addAll(List.of(hit.path("doc_id").textValue(),hit.path("chunk_id").textValue(),digest(hit.path("title").textValue().getBytes(StandardCharsets.UTF_8)),digest(hit.path("snippet").textValue().getBytes(StandardCharsets.UTF_8)),hit.path("score_ppm").asText(),hit.path("embedding_model").textValue()));
        assertEquals(digest(String.join("\n",canonical).getBytes(StandardCharsets.UTF_8)),data.path("resource_version").textValue());
        assertEquals(data,success(input));
    }
    @Test void documentScopeAndEmptyScopeNeverBroadenToAllKnowledge()throws Exception{
        var input=input();input.putArray("doc_ids").add(docs.get("ready")).add(docs.get("foreign")).add(docs.get("draft"));
        var hits=success(input).path("hits");assertEquals(1,hits.size());assertEquals(docs.get("ready").toString(),hits.get(0).path("doc_id").textValue());
        input.putArray("doc_ids");assertTrue(success(input).path("hits").isEmpty());
    }
    @Test void otherTenantAndOtherSourceSnapshotCannotReadThisRun()throws Exception{
        assertEquals(404,post(input(),"TENANT_B").getResponse().getStatus());
        var input=input();input.put("snapshot_id",UUID.randomUUID().toString());var result=post(input,"TENANT_A");assertEquals(409,result.getResponse().getStatus());assertEquals(40905,root(result).path("code").intValue());
    }
    @Test void takeoverClosureHiddenOrChangedSourceAndExpiryDenyFacts()throws Exception{
        success(input());admin("UPDATE session SET agent_id=12345 WHERE id="+sessionId);denied(404,40402);
        admin("UPDATE session SET agent_id=NULL,status=3 WHERE id="+sessionId);denied(404,40402);
        admin("UPDATE session SET status=2 WHERE id="+sessionId);admin("UPDATE session_message SET visible_to=2 WHERE session_id="+sessionId);denied(404,40402);
        admin("UPDATE session_message SET visible_to=1,content='changed' WHERE session_id="+sessionId);denied(409,40902);
        admin("UPDATE session_message SET content='PRIVATE-SOURCE' WHERE session_id="+sessionId);
        admin("UPDATE agent_source_snapshot SET created_at=clock_timestamp()-interval '2 hours',expires_at=clock_timestamp()-interval '1 hour' WHERE event_id='"+event.eventId()+"'");denied(410,41002);
    }
    @Test void stateAndModelChangesAreFreshAndDoNotReusePreviousResult()throws Exception{
        var input=input();input.putArray("doc_ids").add(docs.get("ready"));var first=success(input);
        admin("UPDATE kb_document SET status=4 WHERE id="+docs.get("ready"));assertTrue(success(input).path("hits").isEmpty());
        admin("UPDATE kb_document SET status=3,index_status=4 WHERE id="+docs.get("ready"));assertTrue(success(input).path("hits").isEmpty());
        admin("UPDATE kb_document SET index_status=3 WHERE id="+docs.get("ready"));assertEquals(first,success(input));
        input.put("embedding_model","fixture:other-model");assertTrue(success(input).path("hits").isEmpty());
    }
    @Test void parallelQueriesHaveNoNewMessagesOrReceiptsOrBillingEvents()throws Exception{
        int events=committedEvents.get();var expected=success(input());try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            var results=new ArrayList<java.util.concurrent.Future<JsonNode>>();for(int i=0;i<6;i++)results.add(executor.submit(()->success(input())));
            for(var future:results)assertEquals(expected,future.get(10,TimeUnit.SECONDS));
        }
        tx(()->{assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM session_message WHERE session_id=?",Integer.class,sessionId));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM agent_result_receipt WHERE run_id=?",Integer.class,run));return null;});assertEquals(events,committedEvents.get());
    }
    @Test void selectedKnowledgeAndSourceAreLockedUntilReadTransactionEnds(){
        tx(()->{new AgentKnowledgeQueries(jdbc).read("TENANT_A",event.eventId(),run,command());
            for(String sql:List.of("UPDATE kb_document SET title=title WHERE id="+docs.get("ready"),"UPDATE kb_chunk SET content=content WHERE id="+docs.get("ready"),"UPDATE session SET agent_id=12345 WHERE id="+sessionId)){
                try(var db=DriverManager.getConnection(System.getenv("SUPPORTFLOW_RLS_TEST_URL"),"supportflow",System.getenv("SUPPORTFLOW_RLS_TEST_MIGRATION_PASSWORD"));var statement=db.createStatement()){
                    statement.execute("SET lock_timeout='20ms'");var failure=assertThrows(java.sql.SQLException.class,()->statement.execute(sql));assertEquals("55P03",failure.getSQLState());
                }catch(Exception failure){throw new IllegalStateException(failure);}
            }return null;});
    }
    @Test void databaseTimeExpiryAfterKnowledgeQueryIsRecheckedBeforeReturningFacts(){
        var wrapper=new JdbcTemplate(jdbc.getDataSource()){
            @Override public <T>List<T> query(String sql,RowMapper<T> mapper,Object... args){
                var values=super.query(sql,mapper,args);
                if(sql.contains("FROM kb_chunk c"))admin("UPDATE agent_source_snapshot SET created_at=clock_timestamp()-interval '2 hours',expires_at=clock_timestamp()-interval '1 hour' WHERE event_id='"+event.eventId()+"'");
                return values;
            }
        };
        var failure=assertThrows(BizException.class,()->tx(()->new AgentKnowledgeQueries(wrapper).read("TENANT_A",event.eventId(),run,command())));
        assertEquals(41002,failure.getCode());
    }
    @Test void restrictedRoleSqlFailureHasNoRawSqlOrPrivateBody()throws Exception{
        admin("REVOKE SELECT ON kb_chunk FROM supportflow_customer_tenant");
        try{var result=post(input(),"TENANT_A");assertEquals(503,result.getResponse().getStatus());assertFalse(result.getResponse().getContentAsString().contains("SELECT"));assertFalse(result.getResponse().getContentAsString().contains("PRIVATE"));}
        finally{admin("GRANT SELECT ON kb_chunk TO supportflow_customer_tenant");}
    }
    private static String digest(byte[] raw){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));}catch(Exception failure){throw new AssertionError(failure);}}
}
