package io.supportflow.customer.controller.bot;

import io.supportflow.common.context.LoginUserContext;
import io.supportflow.common.api.ApiResponse;
import io.supportflow.common.auth.LoginUser;
import io.supportflow.customer.dto.bot.BotHealthVO;
import io.supportflow.customer.service.bot.BotHealthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


/**
 * 「智能客服不回话」的一键体检。
 *
 * <p>机器人回一句话要跨三个服务：customer-service（落库与编排）→ supportflow-ai（意图/情绪/回答）
 * → supportflow-realtime-service（把回复推给客户）。任何一环没起、或者是旧版本，客户侧看到的
 * 现象都一模一样：**发出去没人理**。日志散在三个服务里，靠翻日志定位很慢。</p>
 *
 * <p>探测与结论都在 {@link BotHealthService} 里，控制器只包一层响应。</p>
 */
@RestController
@RequestMapping("/api/customer/bot")
public class BotHealthController {

    private final BotHealthService botHealthService;

    public BotHealthController(BotHealthService botHealthService) {
        this.botHealthService = botHealthService;
    }

    /**
     * 机器人链路体检（需要坐席登录态，走租户鉴权）。
     */
    @GetMapping("/health")
    public ApiResponse<BotHealthVO> health() {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(botHealthService.health(user.tenantCode()));
    }
}
