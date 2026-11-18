package io.supportflow.customer.wechat;

import io.supportflow.common.exception.BizException;
import io.supportflow.customer.dto.wechat.TokenResult;
import io.supportflow.customer.entity.WeChatChannelConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * access_token 缓存。
 *
 * <p>微信的 access_token 全局唯一、有效期 7200 秒，而且**新的一次获取会让旧的立刻失效**：
 * 多实例各拿一次就会互相踢掉。所以这里做两件事：
 * ① 进程内缓存，过期前 5 分钟才刷新；② 同一个 AppID 用锁串行化刷新，避免并发重复获取。</p>
 */
@Service
public class WeChatAccessTokenService {

    private static final Logger log = LoggerFactory.getLogger(WeChatAccessTokenService.class);
    private static final long REFRESH_AHEAD_SECONDS = 300;

    private final WeChatApiClient apiClient;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public WeChatAccessTokenService(WeChatApiClient apiClient) {
        this.apiClient = apiClient;
    }

    public String token(WeChatChannelConfig config) {
        if (config == null || config.getAppId() == null || config.getAppSecret() == null) {
            throw new BizException(40001, "公众号渠道还没配置 AppID / AppSecret");
        }
        String appId = config.getAppId();
        Cached cached = cache.get(appId);
        if (cached != null && cached.usable()) {
            return cached.token();
        }
        synchronized (this) {
            Cached current = cache.get(appId);
            if (current != null && current.usable()) {
                return current.token();
            }
            TokenResult result = apiClient.accessToken(appId, config.getAppSecret());
            if (result.token() == null || result.token().isBlank()) {
                throw new BizException(50002, "微信没有返回 access_token，请检查 AppID / AppSecret");
            }
            cache.put(appId, new Cached(result.token(),
                    Instant.now().plusSeconds(Math.max(60, result.expiresIn() - REFRESH_AHEAD_SECONDS))));
            log.info("刷新微信 access_token appId={} 有效期={}s", appId, result.expiresIn());
            return result.token();
        }
    }

    /** 微信报 40001/42001（token 失效）时，主动清缓存，下次调用会重新获取 */
    public void invalidate(String appId) {
        cache.remove(appId);
    }

    private record Cached(String token, Instant expireAt) {
        boolean usable() {
            return Instant.now().isBefore(expireAt);
        }
    }
}
