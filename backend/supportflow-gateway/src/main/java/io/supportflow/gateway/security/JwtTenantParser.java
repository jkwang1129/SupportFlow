package io.supportflow.gateway.security;

import io.supportflow.gateway.config.TenantGuardProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网关侧只做一件事：从令牌里**验签**取出 tenant_code（HS256，与 user-service 同一个密钥）。
 *
 * <p>为什么自己解而不是引 jjwt：网关只需要 sub / tnt / name 三个声明，
 * 手写 20 行不引入新依赖（网关是入口，依赖越少越好维护）；验签算法与 user-service 的
 * Keys.hmacShaKeyFor(secret) 完全一致（HMAC-SHA256）。</p>
 *
 * <p>声明取值用一个小正则而不是 JSON 库：JWT 的 payload 是我们自己签的**扁平结构**
 * （sub/uno/name/type/tnt/sno/iat/exp），不需要完整 JSON 解析能力；
 * 这样网关也就不用关心项目里用的是 Jackson 2 还是 Jackson 3。</p>
 */
@Component
public class JwtTenantParser {

    private final byte[] secret;

    public JwtTenantParser(TenantGuardProperties properties) {
        this.secret = properties.getJwtSecret().getBytes(StandardCharsets.UTF_8);
    }

    /** 解析结果：验签失败返回 null（调用方按"未认证"处理，不在这里抛异常） */
    public ParsedToken parse(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return null;
        }
        try {
            String signingInput = parts[0] + "." + parts[1];
            if (!verify(signingInput, parts[2], algorithmOf(parts[0]))) {
                return null;
            }
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            long exp = longClaim(payload, "exp");
            if (exp > 0 && exp * 1000 < System.currentTimeMillis()) {
                return null;
            }
            return new ParsedToken(
                    stringClaim(payload, "tnt"),
                    stringClaim(payload, "sub"),
                    stringClaim(payload, "name"),
                    exp);
        } catch (Exception e) {
            return null;
        }
    }

    /** 取字符串声明（找不到返回 null）；网关内共享的小工具，也用于解析内部接口的响应 */
    public static String stringClaim(String json, String name) {
        Matcher matcher = Pattern.compile("\"" + name + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** 取数字声明（找不到返回 0） */
    public static long longClaim(String json, String name) {
        Matcher matcher = Pattern.compile("\"" + name + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : 0L;
    }

    /** 取 JWT 头里的 alg（解不开就是 null）。 */
    private static String algorithmOf(String headerPart) {
        try {
            String header = new String(Base64.getUrlDecoder().decode(headerPart), StandardCharsets.UTF_8);
            return stringClaim(header, "alg");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 按令牌头声明的算法验签。
     *
     * <p>为什么不能写死 HS256：user-service 用的是 jjwt 的
     * {@code Keys.hmacShaKeyFor(secret)}，它按密钥长度自动挑算法——本项目密钥 66 字节，
     * jjwt 选的是 <b>HS512</b>。网关这边写死 HmacSHA256 的话，真登录令牌一律验签失败，
     * {@link #parse} 返回 null，调用方按"未认证"放行：租户头篡改不拦、冻结不生效、
     * 限流还能被 X-Tenant-Code 牵着走。所以这里必须读令牌头的 alg。</p>
     *
     * <p>只认 HS256 / HS384 / HS512（共用同一个密钥）；{@code alg=none}、
     * RS256 等一律拒绝，避免"改个头就免验签"的经典漏洞。</p>
     */
    private boolean verify(String signingInput, String signature, String alg) throws Exception {
        String macAlgorithm = switch (alg == null ? "" : alg) {
            case "HS256" -> "HmacSHA256";
            case "HS384" -> "HmacSHA384";
            case "HS512" -> "HmacSHA512";
            default -> null;
        };
        if (macAlgorithm == null) {
            return false;
        }
        Mac mac = Mac.getInstance(macAlgorithm);
        mac.init(new SecretKeySpec(secret, macAlgorithm));
        byte[] expected = mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));
        return MessageDigest.isEqual(expected, Base64.getUrlDecoder().decode(signature));
    }

    /** 令牌里我们关心的三个声明 */
    public record ParsedToken(String tenantCode, String userId, String userName, long expireAt) {
    }
}
