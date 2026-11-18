package io.supportflow.realtime.ws;

import io.supportflow.realtime.security.RealtimePrincipal;
import io.supportflow.realtime.security.RealtimeTokenParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 握手鉴权：浏览器建连时带不了自定义请求头，令牌通过 {@code ?token=xxx} 传。
 * 校验不通过直接 401 拒绝握手，连不上就不会占用服务端连接资源。
 *
 * <p>日志要限流：令牌过期的标签页会一直重连（每 15 秒一次），
 * 每次都打一条 WARN 的话，一天往日志里灌好几千行同样的内容，
 * 真正要看的错误反而被淹了。所以同一个令牌每 5 分钟最多 WARN 一次，其余降到 DEBUG。</p>
 */
@Component
public class AuthHandshakeInterceptor implements HandshakeInterceptor {

    public static final String PRINCIPAL_KEY = "realtimePrincipal";

    private static final Logger log = LoggerFactory.getLogger(AuthHandshakeInterceptor.class);

    /** 同一条令牌多久最多告警一次 */
    private static final long WARN_INTERVAL_MILLIS = 5 * 60 * 1000L;

    /** 令牌指纹 → 上次告警时间（只放指纹，不放令牌本身） */
    private final Map<String, Long> warnedAt = new ConcurrentHashMap<>();

    private final RealtimeTokenParser tokenParser;

    public AuthHandshakeInterceptor(RealtimeTokenParser tokenParser) {
        this.tokenParser = tokenParser;
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes
    ) {
        String token = UriComponentsBuilder.fromUri(request.getURI()).build()
                .getQueryParams().getFirst("token");
        try {
            RealtimePrincipal principal = tokenParser.parse(token);
            attributes.put(PRINCIPAL_KEY, principal);
            return true;
        } catch (Exception e) {
            warnOnce(token, request.getURI().toString(), e.getMessage());
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
    }

    /** 同一个令牌限流告警：第一次说清楚，之后不再刷屏。 */
    private void warnOnce(String token, String uri, String reason) {
        String fingerprint = fingerprint(token);
        long now = System.currentTimeMillis();
        Long last = warnedAt.get(fingerprint);
        if (last != null && now - last < WARN_INTERVAL_MILLIS) {
            log.debug("长连接鉴权失败（已限流） token={} uri={} reason={}", fingerprint, uri, reason);
            return;
        }
        warnedAt.put(fingerprint, now);
        // 顺手清掉过期条目，别让 Map 随着访问量一直涨
        if (warnedAt.size() > 500) {
            warnedAt.entrySet().removeIf(entry -> now - entry.getValue() > WARN_INTERVAL_MILLIS);
        }
        log.warn("长连接鉴权失败 uri={} reason={} token={}\n"
                        + "  → 令牌无效或已过期。坐席端会在连续失败几次后确认登录态，"
                        + "确认过期就提示重新登录；同一条令牌 5 分钟内不再重复告警。",
                uri, reason, fingerprint);
    }

    /**
     * 令牌指纹：取 SHA-256 的前 8 位十六进制。
     *
     * <p>不能直接截令牌开头——JWT 的头部（alg/typ）对所有令牌都一样，截了也没法区分；
     * 而完整令牌写进日志等于泄露凭据。哈希前缀既能对齐同一条令牌，又不可逆。</p>
     */
    private String fingerprint(String token) {
        if (token == null || token.isBlank()) {
            return "empty";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(8);
            for (int i = 0; i < 4; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (Exception e) {
            // 拿不到摘要就不带令牌信息，别让日志本身出错
            return "unknown";
        }
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception
    ) {
        // 无需处理
    }
}
