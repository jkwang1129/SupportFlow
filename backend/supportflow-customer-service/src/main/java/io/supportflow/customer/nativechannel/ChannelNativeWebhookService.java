package io.supportflow.customer.nativechannel;

import io.supportflow.common.exception.BizException;
import io.supportflow.common.context.TenantContext;
import io.supportflow.customer.security.ChannelTenantResolver;
import io.supportflow.customer.dto.channel.AppInboundResultVO;
import io.supportflow.customer.dto.channel.AppMessage;
import io.supportflow.customer.dto.channel.MailInbound;
import io.supportflow.customer.dto.channel.MailInboundResultVO;
import io.supportflow.customer.dto.channel.MailMessage;
import io.supportflow.customer.dto.channel.PhoneCall;
import io.supportflow.customer.dto.channel.PhoneEvent;
import io.supportflow.customer.dto.channel.PhoneEventResultVO;
import io.supportflow.customer.dto.channel.TtsPayload;
import io.supportflow.customer.dto.channel.TtsResult;
import io.supportflow.customer.entity.Channel;
import io.supportflow.customer.service.phone.PhoneCallSpeechService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 原生渠道入站 webhook 的业务：App / 邮件网关 / 呼叫中心回调的验签、报文解析与分发。
 *
 * <p>渠道身份靠路径上的渠道密钥认（公开标识），请求真伪靠 HMAC 签名认
 * （{@code X-SupportFlow-Timestamp} + {@code X-SupportFlow-Signature}，密钥在渠道配置里）。
 * 之所以用"原始 body 字符串"接参：签名是对<b>原始报文</b>算的，
 * 先反序列化再拼回去会因空格/顺序变化验签失败——这条约束落在控制器签名上，
 * 校验与解析的逻辑放这里。</p>
 */
@Service
public class ChannelNativeWebhookService {

    private static final Logger log = LoggerFactory.getLogger(ChannelNativeWebhookService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChannelNativeConfigService configService;
    private final ChannelNativeInboundService inboundService;
    private final PhoneCallSpeechService phoneCallSpeechService;
    private final ChannelTenantResolver channelTenantResolver;

    public ChannelNativeWebhookService(ChannelNativeConfigService configService,
                                       ChannelNativeInboundService inboundService,
                                       PhoneCallSpeechService phoneCallSpeechService,
                                       ChannelTenantResolver channelTenantResolver) {
        this.configService = configService;
        this.inboundService = inboundService;
        this.phoneCallSpeechService = phoneCallSpeechService;
        this.channelTenantResolver = channelTenantResolver;
    }

    /** App 用户发消息。 */
    public AppInboundResultVO appMessage(String appKey, String timestamp, String signature, String body) {
        return inChannel(appKey, 4, channel -> {
        Map<String, Object> config = configService.raw(channel.getTenantCode(), channel.getId());
        verify(secretOf(config, "pushSecret"), timestamp, signature, body);
        AppMessage payload = parse(body, AppMessage.class);
        return inboundService.appMessage(channel, payload.userId(), payload.userName(),
                payload.content(), payload.clientMsgNo());
        });
    }

    /** 邮件网关把新邮件推过来。 */
    public MailInboundResultVO mailInbound(String appKey, String timestamp, String signature, String body) {
        return inChannel(appKey, 6, channel -> {
        Map<String, Object> config = configService.raw(channel.getTenantCode(), channel.getId());
        // 邮件渠道现在只支持方案 A（平台直连企业邮箱收信），网关回调只留给早期已配好签名密钥的
        // 渠道做兼容。没配密钥的一律拒绝：AppKey 不是秘密，放任未签名请求进来等于谁都能伪造来信。
        String secret = secretOf(config, "inboundWebhookSecret");
        if (secret == null || secret.isBlank()) {
            throw new BizException(40001, "邮件渠道现在只用 IMAP 直连收信，不再接受网关回调（请在渠道配置里填 IMAP 账号）");
        }
        verify(secret, timestamp, signature, body);
        MailMessage payload = parse(body, MailMessage.class);
        return inboundService.mailInbound(channel, new MailInbound(
                payload.from(), payload.fromName(), payload.subject(), payload.text(), payload.messageId()));
        });
    }

    /**
     * 呼叫中心事件：incoming 呼入 / dtmf 按键 / answered 坐席接听 / recording 录音回传 /
     * no_answer 未接 / abandoned 客户放弃 / hangup 挂机。
     */
    public PhoneEventResultVO phoneEvent(String appKey, String timestamp, String signature, String body) {
        return inChannel(appKey, 5, channel -> {
        Map<String, Object> config = configService.raw(channel.getTenantCode(), channel.getId());
        verify(secretOf(config, "webhookSecret"), timestamp, signature, body);
        PhoneCall payload = parse(body, PhoneCall.class);
        return inboundService.phoneEvent(channel, new PhoneEvent(
                payload.event(), payload.callId(), payload.from(), payload.digits(),
                payload.intent(), payload.action(), payload.duration(),
                payload.recordingUrl(), payload.agentName(), payload.reason()));
        });
    }

    /**
     * 400 IVR 播报语音合成：把播报文案变成音频字节（话务平台不用自己做 TTS）。
     *
     * <p>签名口径与 {@code /event} 完全一致（同一个 webhookSecret、同一套 timestamp + body 签名）——
     * 这个接口能按任意文本合成音频，不能裸奔成公开 TTS。</p>
     */
    public TtsResult synthesizeTts(String appKey, String timestamp,
                                                  String signature, String body) {
        return inChannel(appKey, 5, channel -> {
        Map<String, Object> config = configService.raw(channel.getTenantCode(), channel.getId());
        verify(secretOf(config, "webhookSecret"), timestamp, signature, body);
        TtsPayload payload = parse(body, TtsPayload.class);
        if (payload.text() == null || payload.text().isBlank()) {
            throw new BizException(40001, "要合成的播报文案为空");
        }
        TtsResult result = phoneCallSpeechService.synthesize(
                channel.getTenantCode(), payload.text(), payload.voice(), payload.format());
        if (!result.available()) {
            // 没配语音能力不是故障：如实告诉话务侧"自己 TTS 或用播报文本"，不要返回一段空音频
            throw new BizException(50001, result.hint().isBlank() ? "语音能力不可用" : result.hint());
        }
        log.info("400 IVR 播报合成 tenant={} appKey={} {}字 → {}字节 {}",
                channel.getTenantCode(), appKey, payload.text().length(),
                result.audio().length, result.contentType());
        return result;
        });
    }

    private <T> T inChannel(String appKey, int type, java.util.function.Function<Channel, T> action) {
        String tenant = channelTenantResolver.resolve(appKey);
        return TenantContext.bind(tenant, () -> {
            Channel channel = requireChannel(appKey, type);
            if (!tenant.equals(channel.getTenantCode())) {
                throw new BizException(40301, "渠道与租户不一致");
            }
            return action.apply(channel);
        });
    }

    private Channel requireChannel(String appKey, int expectedType) {
        Channel channel = configService.channelByAppKey(appKey);
        if (channel.getChannelType() == null || channel.getChannelType() != expectedType) {
            throw new BizException(40001, "这个渠道密钥不属于该类型的原生渠道（期望 channelType=" + expectedType + "）");
        }
        return channel;
    }

    private void verify(String secret, String timestamp, String signature, String body) {
        if (!NativeSignature.verify(secret, timestamp, signature, body, System.currentTimeMillis())) {
            log.warn("原生渠道回调验签失败 timestamp={}", timestamp);
            throw new BizException(40301, "回调签名校验失败（请检查签名密钥与时间戳）");
        }
    }

    private static String secretOf(Map<String, Object> config, String field) {
        Object value = config.get(field);
        return value == null ? null : String.valueOf(value);
    }

    private static <T> T parse(String body, Class<T> type) {
        try {
            return MAPPER.readValue(body, type);
        } catch (Exception e) {
            throw new BizException(40001, "回调报文不是合法 JSON：" + e.getMessage());
        }
    }
}
