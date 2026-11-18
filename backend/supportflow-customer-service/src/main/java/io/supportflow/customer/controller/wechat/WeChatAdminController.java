package io.supportflow.customer.controller.wechat;

import io.supportflow.common.context.LoginUserContext;
import io.supportflow.common.api.ApiResponse;
import io.supportflow.common.auth.LoginUser;
import io.supportflow.customer.dto.wechat.SaveCommand;
import io.supportflow.customer.dto.wechat.SimulateBody;
import io.supportflow.customer.dto.wechat.SubscribeLogVO;
import io.supportflow.customer.dto.wechat.SubscribeTestVO;
import io.supportflow.customer.dto.wechat.TemplateTestVO;
import io.supportflow.customer.dto.wechat.TestSubscribeBody;
import io.supportflow.customer.dto.wechat.TestTemplateBody;
import io.supportflow.customer.dto.wechat.WeChatDetailVO;
import io.supportflow.customer.dto.wechat.WeChatSaveVO;
import io.supportflow.customer.dto.wechat.WeChatSimulateVO;
import io.supportflow.customer.wechat.WeChatAdminService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 公众号原生接入的配置与联调（登录态，管理端用）。
 * 配置项就是公众号后台"服务器配置 + 网页授权 + 模板消息"要用的那几项；
 * 联调模拟器复用真实进线链路，不需要等公众号审核也能验证"消息 → 会话 → 机器人 → 回复"。
 */
@RestController
@RequestMapping("/api/customer/channels/{channelId}/wechat")
public class WeChatAdminController {

    private final WeChatAdminService adminService;

    public WeChatAdminController(WeChatAdminService adminService) {
        this.adminService = adminService;
    }

    /**
     * 公众号渠道详情：配置回显 + 回调地址 + 各模板要填的字段。
     */
    @GetMapping
    public ApiResponse<WeChatDetailVO> detail(
            @PathVariable Long channelId) {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(adminService.detail(user.tenantCode(), channelId));
    }

    /**
     * 保存公众号配置（AppID / 密钥 / 令牌 / 模板 ID），保存后回带回调地址。
     */
    @PutMapping
    public ApiResponse<WeChatSaveVO> save(
            @PathVariable Long channelId,
            @RequestBody SaveCommand command) {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(adminService.save(user.tenantCode(), channelId, command, user.name()));
    }

    /**
     * 联调模拟器：伪造一条微信推来的文本消息，走完全一样的落库与机器人接待链路。
     */
    @PostMapping("/simulate")
    public ApiResponse<WeChatSimulateVO> simulate(
            @PathVariable Long channelId,
            @RequestBody SimulateBody body) {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(adminService.simulate(user.tenantCode(), channelId, body));
    }

    /**
     * 发一条模板消息测试（工单进展 / 物流 / 优惠券），验证配置对不对。
     */
    @PostMapping("/test-template")
    public ApiResponse<TemplateTestVO> testTemplate(
            @PathVariable Long channelId,
            @RequestBody TestTemplateBody body) {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(adminService.testTemplate(user.tenantCode(), channelId, body));
    }

    /**
     * 小程序订阅消息：发一条测试消息（用最近绑定过的那个用户）。
     *
     * <p>为什么必须有这个按钮：小程序订阅消息的一次性订阅机制决定了"发不出去"极其常见
     * （客户没授权、次数用完、模板字段不符），只有在配置页能立刻试一次、并且能看到失败原因，
     * 企业才知道自己配的对不对。</p>
     */
    @PostMapping("/test-subscribe")
    public ApiResponse<SubscribeTestVO> testSubscribe(
            @PathVariable Long channelId,
            @RequestBody(required = false) TestSubscribeBody body) {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(adminService.testSubscribe(user.tenantCode(), channelId, body));
    }

    /**
     * 最近的订阅消息发送记录（页面展示"发了没、为什么没发"）
     */
    @GetMapping("/subscribe-logs")
    public ApiResponse<List<SubscribeLogVO>> subscribeLogs(
            @PathVariable Long channelId,
            @RequestParam(required = false, defaultValue = "10") Integer limit) {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(adminService.subscribeLogs(user.tenantCode(), limit));
    }
}
