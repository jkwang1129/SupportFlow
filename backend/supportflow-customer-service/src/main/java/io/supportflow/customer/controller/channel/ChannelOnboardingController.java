package io.supportflow.customer.controller.channel;

import io.supportflow.common.context.LoginUserContext;
import io.supportflow.common.api.ApiResponse;
import io.supportflow.common.auth.LoginUser;
import io.supportflow.customer.dto.channel.ChannelResult;
import io.supportflow.customer.dto.channel.CreateBody;
import io.supportflow.customer.dto.channel.StatusBody;
import io.supportflow.customer.service.channel.ChannelOnboardingService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 企业渠道接入（官网 / 公众号 / 小程序）。
 */
@RestController
@RequestMapping("/api/customer/channels")
public class ChannelOnboardingController {

    private final ChannelOnboardingService channelService;

    public ChannelOnboardingController(
            ChannelOnboardingService channelService
    ) {
        this.channelService = channelService;
    }

    /**
     * 新增渠道接入：同一企业同一类型只允许一条生效中的记录。
     */
    @PostMapping
    public ApiResponse<ChannelResult> create(
            @RequestBody CreateBody body
    ) {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(channelService.create(user, body.channelType(), body.name(), body.desc()));
    }

    /**
     * 渠道列表（每个渠道带自己的接入密钥与状态）。
     */
    @GetMapping
    public ApiResponse<List<ChannelResult>> list() {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(channelService.list(user));
    }

    /**
     * 重新生成渠道密钥（旧密钥立即失效，页面要提示已切换）。
     */
    @PostMapping("/{channelId}/key/rotate")
    public ApiResponse<ChannelResult> rotateKey(
            @PathVariable long channelId
    ) {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(channelService.rotateKey(user, channelId));
    }

    /**
     * 启用 / 停用渠道（停用后访客端会提示"渠道已停用"）。
     */
    @PostMapping("/{channelId}/status")
    public ApiResponse<ChannelResult> updateStatus(
            @PathVariable long channelId,
            @RequestBody StatusBody body
    ) {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(channelService.updateStatus(user, channelId, body.enabled()));
    }

}
