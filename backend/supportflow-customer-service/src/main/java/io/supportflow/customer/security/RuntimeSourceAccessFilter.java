package io.supportflow.customer.security;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.supportflow.common.auth.CustomerInternalCredential;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/** Authenticating this narrow service route must precede schema queries and body parsing. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 15)
public final class RuntimeSourceAccessFilter extends OncePerRequestFilter {
    private static final String AUDIENCE = "supportflow-customer-runtime:v1";
    private static final String UUID_PATTERN = "[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}";
    private static final Pattern ROUTE = Pattern.compile("/api/customer/internal/runtime/events/(" + UUID_PATTERN + ")/runs/(" + UUID_PATTERN + ")/(snapshot|reply|tickets/([A-Za-z0-9][A-Za-z0-9_-]{0,63})|knowledge/search|ticket-create)");
    private static final Pattern CREDENTIAL = Pattern.compile("runtime:ai:([0-9]{1,12}):([0-9a-f]{64}):([0-9a-f]{64})");
    private final ObjectMapper mapper = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final String secret;
    private final boolean sourceEnabled;
    private final boolean resultEnabled;
    private final boolean readToolsEnabled;
    private final boolean ticketCreateEnabled;
    private final Clock clock;

    @Autowired
    public RuntimeSourceAccessFilter(@Value("${SUPPORTFLOW_AI_CUSTOMER_SECRET:}") String secret,
                                     @Value("${supportflow.bot.runtime-source-enabled:false}") boolean enabled,
                                     @Value("${supportflow.bot.runtime-result-enabled:false}") boolean resultEnabled,
                                     @Value("${supportflow.bot.runtime-read-tools-enabled:false}") boolean readToolsEnabled,
                                     @Value("${supportflow.bot.runtime-ticket-create-enabled:false}") boolean ticketCreateEnabled,
                                     @Value("${SUPPORTFLOW_CUSTOMER_REALTIME_SECRET:}") String realtime,
                                     @Value("${SUPPORTFLOW_CUSTOMER_OPS_SECRET:}") String ops,
                                     @Value("${SUPPORTFLOW_CUSTOMER_TENANT_SECRET:}") String tenant) {
        this(secret, enabled, resultEnabled, readToolsEnabled,ticketCreateEnabled, List.of(realtime, ops, tenant), Clock.systemUTC());
    }
    public RuntimeSourceAccessFilter(String secret,boolean enabled,boolean resultEnabled,boolean readToolsEnabled,String realtime,String ops,String tenant){
        this(secret,enabled,resultEnabled,readToolsEnabled,false,List.of(realtime,ops,tenant),Clock.systemUTC());
    }
    public RuntimeSourceAccessFilter(String secret,boolean enabled,boolean resultEnabled,String realtime,String ops,String tenant) {
        this(secret,enabled,resultEnabled,false,List.of(realtime,ops,tenant),Clock.systemUTC());
    }
    public RuntimeSourceAccessFilter(String secret,boolean enabled,String realtime,String ops,String tenant) {
        this(secret,enabled,false,List.of(realtime,ops,tenant),Clock.systemUTC());
    }
    RuntimeSourceAccessFilter(String secret, boolean enabled, List<String> otherSecrets, Clock clock) {
        this(secret,enabled,false,otherSecrets,clock);
    }
    RuntimeSourceAccessFilter(String secret, boolean enabled, boolean resultEnabled, List<String> otherSecrets, Clock clock) {
        this(secret,enabled,resultEnabled,false,otherSecrets,clock);
    }
    RuntimeSourceAccessFilter(String secret, boolean enabled, boolean resultEnabled, boolean readToolsEnabled, List<String> otherSecrets, Clock clock) {
        this(secret,enabled,resultEnabled,readToolsEnabled,false,otherSecrets,clock);
    }
    RuntimeSourceAccessFilter(String secret, boolean enabled, boolean resultEnabled, boolean readToolsEnabled,boolean ticketCreateEnabled, List<String> otherSecrets, Clock clock) {
        if ((enabled || resultEnabled || readToolsEnabled || ticketCreateEnabled) && !CustomerInternalCredential.usable(secret)) throw new IllegalArgumentException("Runtime service credential is not configured");
        if (CustomerInternalCredential.usable(secret) && otherSecrets.stream().filter(CustomerInternalCredential::usable).anyMatch(secret::equals))
            throw new IllegalArgumentException("Runtime source credential must be distinct from other callers");
        this.secret = secret; this.sourceEnabled = enabled; this.resultEnabled=resultEnabled; this.readToolsEnabled=readToolsEnabled; this.ticketCreateEnabled=ticketCreateEnabled; this.clock = clock;
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!path.startsWith("/api/customer/internal/runtime/")) { chain.doFilter(request, response); return; }
        var credentials = Collections.list(request.getHeaders(CustomerInternalCredential.HEADER));
        var tenants = Collections.list(request.getHeaders("X-Tenant-Code"));
        var parsed = credentials.size() == 1 ? CREDENTIAL.matcher(credentials.getFirst()) : null;
        if (parsed == null || !parsed.matches() || tenants.size() != 1 || !CustomerInternalCredential.usable(secret)) {
            reject(response, 401, "Runtime service authentication required"); return;
        }
        long now = clock.instant().getEpochSecond(), expiry = Long.parseLong(parsed.group(1));
        String tenant = tenants.getFirst();
        String canonical = String.join("\n", AUDIENCE, "ai", parsed.group(1), request.getMethod(), request.getRequestURI(),
                request.getQueryString() == null ? "" : request.getQueryString(), tenant, parsed.group(2));
        if (expiry <= now || expiry > now + 35 || !MessageDigest.isEqual(HexFormat.of().parseHex(parsed.group(3)), mac(canonical))) {
            reject(response, 401, "Invalid runtime service credential"); return;
        }
        var route = ROUTE.matcher(path);
        if (!route.matches() || !(request.getMethod().equals("GET") || request.getMethod().equals("POST"))
                || request.getQueryString() != null || !tenant.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,15}") || tenant.equalsIgnoreCase("PLATFORM")) {
            reject(response, 403, "Runtime operation is not authorized"); return;
        }
        boolean reply=route.group(3).equals("reply");
        boolean ticket=route.group(4)!=null;
        boolean knowledge=route.group(3).equals("knowledge/search");
        boolean ticketCreate=route.group(3).equals("ticket-create");
        if (ticket && !request.getMethod().equals("GET")) { reject(response,403,"Runtime read operation requires GET"); return; }
        if (knowledge && !request.getMethod().equals("POST")) { reject(response,403,"Runtime knowledge operation requires POST"); return; }
        boolean allowed=ticketCreate ? (request.getMethod().equals("POST") ? ticketCreateEnabled : readToolsEnabled)
                : ticket || knowledge ? readToolsEnabled : reply ? resultEnabled : sourceEnabled;
        if (!allowed) { reject(response, 503, "Runtime API is disabled"); return; }
        // Exact write JSON may escape each content byte as six characters.
        // The parsed content still has the independent 8192 UTF-8 byte bound.
        int limit=reply || knowledge || ticketCreate ? 65536 : 8192;
        if (request.getContentLengthLong() > limit) { reject(response, 413, "Runtime body is too large"); return; }
        byte[] body = request.getInputStream().readNBytes(limit+1);
        if (body.length > limit) { reject(response, 413, "Runtime body is too large"); return; }
        if (clock.instant().getEpochSecond()>=expiry || !MessageDigest.isEqual(HexFormat.of().parseHex(parsed.group(2)), sha(body))) {
            reject(response, 401, "Runtime body does not match its credential"); return;
        }
        var call=new RuntimeCustomerCall(tenant, UUID.fromString(route.group(1)), UUID.fromString(route.group(2)));
        if (request.getMethod().equals("GET")) {
            if (body.length != 0) { reject(response, 400, "Runtime GET cannot carry a body"); return; }
        } else {
            var types = Collections.list(request.getHeaders("Content-Type"));
            if (types.size() != 1 || !types.getFirst().split(";", 2)[0].trim().equalsIgnoreCase("application/json")) {
                reject(response, 415, "Runtime source request must use JSON"); return;
            }
            try {
                var object = mapper.readTree(body);
                if (object == null || !object.isObject()) { reject(response, 400, "Runtime request must be an object"); return; }
                if (reply) {
                    try { request.setAttribute(RuntimeReplyCommand.ATTRIBUTE,RuntimeReplyCommand.parse(object,call)); }
                    catch (IllegalArgumentException invalid) { reject(response,422,"Invalid runtime reply request"); return; }
                } else if(ticketCreate) {
                    try { request.setAttribute(RuntimeTicketCreateCommand.ATTRIBUTE,RuntimeTicketCreateCommand.parse(object,call)); }
                    catch(IllegalArgumentException invalid) { reject(response,422,"Invalid runtime ticket request");return; }
                } else if(knowledge) {
                    try { request.setAttribute(RuntimeKnowledgeCommand.ATTRIBUTE,RuntimeKnowledgeCommand.parse(object,parsed.group(2))); }
                    catch(IllegalArgumentException invalid) { reject(response,422,"Invalid runtime knowledge request");return; }
                } else if (!object.isEmpty()) { reject(response, 400, "Runtime source request must be an empty object"); return; }
            } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
                reject(response, 400, "Invalid runtime JSON request"); return;
            }
        }
        request.setAttribute(RuntimeCustomerCall.ATTRIBUTE,call);
        chain.doFilter(request, response);
    }

    private byte[] mac(String value) {
        try {
            var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException unavailable) { throw new IllegalStateException("Runtime authentication unavailable", unavailable); }
    }
    private static byte[] sha(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (java.security.GeneralSecurityException unavailable) { throw new IllegalStateException(unavailable); }
    }
    private static void reject(HttpServletResponse response, int code, String message) throws IOException {
        response.setStatus(code); response.setContentType("application/json;charset=UTF-8");
        if (code == 503) response.setHeader("Retry-After", "5");
        response.getWriter().write("{\"code\":" + code + ",\"message\":\"" + message + "\",\"data\":null}");
    }
}
