package io.supportflow.customer.controller.channel;

import io.supportflow.common.api.ApiResponse;
import io.supportflow.customer.dto.channel.AppInboundResultVO;
import io.supportflow.customer.dto.channel.MailInboundResultVO;
import io.supportflow.customer.dto.channel.PhoneEventResultVO;
import io.supportflow.customer.dto.channel.TtsResult;
import io.supportflow.customer.nativechannel.ChannelNativeWebhookService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 原生渠道入站 webhook（App / 邮件网关 / 呼叫中心调用，免登录）。
 *
 * <p>这里只按"原始 body 字符串"接参并转发——签名是对<b>原始报文</b>算的，
 * 先反序列化再拼回去会因空格/顺序变化验签失败。验签、解析与分发都在
 * {@link ChannelNativeWebhookService} 里。</p>
 */
@RestController
@RequestMapping("/api/customer/native")
public class ChannelNativeWebhookController {

    private final ChannelNativeWebhookService webhookService;

    public ChannelNativeWebhookController(ChannelNativeWebhookService webhookService) {
        this.webhookService = webhookService;
    }

    /**
     * App 用户发消息
     */
    @PostMapping("/app/{appKey}/message")
    public ApiResponse<AppInboundResultVO> appMessage(
            @PathVariable String appKey,
            @RequestHeader(value = "X-SupportFlow-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-SupportFlow-Signature", required = false) String signature,
            @RequestBody String body) {
        return ApiResponse.ok(webhookService.appMessage(appKey, timestamp, signature, body));
    }

    /**
     * 邮件网关把新邮件推过来
     */
    @PostMapping("/mail/{appKey}/inbound")
    public ApiResponse<MailInboundResultVO> mailInbound(
            @PathVariable String appKey,
            @RequestHeader(value = "X-SupportFlow-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-SupportFlow-Signature", required = false) String signature,
            @RequestBody String body) {
        return ApiResponse.ok(webhookService.mailInbound(appKey, timestamp, signature, body));
    }

    /**
     * 呼叫中心事件：incoming 呼入 / dtmf 按键 / answered 坐席接听 / recording 录音回传 /
     * no_answer 未接 / abandoned 客户放弃 / hangup 挂机。
     */
    @PostMapping("/phone/{appKey}/event")
    public ApiResponse<PhoneEventResultVO> phoneEvent(
            @PathVariable String appKey,
            @RequestHeader(value = "X-SupportFlow-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-SupportFlow-Signature", required = false) String signature,
            @RequestBody String body) {
        return ApiResponse.ok(webhookService.phoneEvent(appKey, timestamp, signature, body));
    }

    /**
     * 400 IVR 播报语音合成：把播报文案变成音频字节（话务平台不用自己做 TTS）。
     *
     * <p>签名口径与 `/event` 完全一致（同一个 webhookSecret、同一套 timestamp + body 签名）——
     * 这个接口能按任意文本合成音频，不能裸奔成公开 TTS。</p>
     */
    @PostMapping("/phone/{appKey}/tts")
    public ResponseEntity<byte[]> phoneTts(
            @PathVariable String appKey,
            @RequestHeader(value = "X-SupportFlow-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-SupportFlow-Signature", required = false) String signature,
            @RequestBody String body) {
        TtsResult result = webhookService.synthesizeTts(appKey, timestamp, signature, body);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(result.contentType()))
                .body(result.audio());
    }
}
