package io.supportflow.customer.controller.session;

import io.supportflow.common.context.LoginUserContext;
import io.supportflow.common.api.ApiResponse;
import io.supportflow.customer.dto.session.ConsolePolicyVO;
import io.supportflow.customer.internal.SecurityOpsClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端显示策略：水印开关 + 空闲登出时长。
 *
 * <p>"登录即可读"——平台账号与企业账号都要用（水印/空闲登出是全站行为）。
 * 之所以放在 customer-service：它已经有连 ops 的内部客户端，
 * 而平台专用的策略接口（含失败阈值、IP 白名单）绝不能给企业账号读。</p>
 */
@RestController
@RequestMapping("/api/customer/security")
public class ConsolePolicyController {

    private final SecurityOpsClient securityOpsClient;

    public ConsolePolicyController(SecurityOpsClient securityOpsClient) {
        this.securityOpsClient = securityOpsClient;
    }

    /**
     * 登录即可读：只回两个字段
     */
    @GetMapping("/console-policy")
    public ApiResponse<ConsolePolicyVO> consolePolicy() {
        LoginUserContext.require();
        return ApiResponse.ok(securityOpsClient.consolePolicy());
    }
}
