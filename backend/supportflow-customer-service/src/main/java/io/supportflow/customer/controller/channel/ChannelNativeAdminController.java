package io.supportflow.customer.controller.channel;

import io.supportflow.common.context.LoginUserContext;
import io.supportflow.common.api.ApiResponse;
import io.supportflow.common.auth.LoginUser;
import io.supportflow.customer.dto.channel.CallPageVO;
import io.supportflow.customer.dto.channel.ChannelNativeDetailVO;
import io.supportflow.customer.dto.channel.ChannelSimulateVO;
import io.supportflow.customer.dto.channel.ConfiguredVO;
import io.supportflow.customer.dto.channel.SimulateBody;
import io.supportflow.customer.nativechannel.ChannelNativeAdminService;
import io.supportflow.customer.nativechannel.ChannelNativeConfigService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 原生渠道配置与联调（登录态，管理端用）：App / 400 热线 / 邮件统一一个页面。
 *
 * <p>字段定义由服务端给（{@link ChannelNativeConfigService#fields(int)}），前端按定义渲染表单，
 * 所以三种渠道共用一个页面；联调模拟器复用真实进线链路，只是把身份标成 {@code sim_} 前缀不真发外呼。</p>
 */
@RestController
@RequestMapping("/api/customer/channels/{channelId}/native")
public class ChannelNativeAdminController {

    private final ChannelNativeAdminService adminService;

    public ChannelNativeAdminController(ChannelNativeAdminService adminService) {
        this.adminService = adminService;
    }

    /**
     * 原生渠道详情：配置回显（密钥掩码）+ 要填的字段 + 回调地址 + 模拟器提示。
     */
    @GetMapping
    public ApiResponse<ChannelNativeDetailVO> detail(
            @PathVariable Long channelId) {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(adminService.detail(user, channelId));
    }

    /**
     * 保存原生渠道配置（字段定义由渠道类型决定，服务端按 schema 校验）。
     *
     * <p>入参是「字段名 → 值」的表单数据：App / 邮件 / 400 热线三套字段完全不同，
     * 清单维护在 {@link io.supportflow.customer.nativechannel.ChannelNativeConfigService}
     * 的 SCHEMA 里（详情接口回的就是它，前端照着渲染表单，新增字段只要加一行）。
     * 这里用 Map 是刻意的——写成实体等于把 schema 抄第二遍，且以后加字段必须发版；
     * 服务端仍会按 schema 逐个校验必填 / 类型 / 下拉取值，不是"来什么存什么"。</p>
     */
    @PutMapping
    public ApiResponse<ConfiguredVO> save(
            @PathVariable Long channelId,
            @RequestBody Map<String, Object> body) {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(adminService.save(user, channelId, body));
    }

    /**
     * 400 通话记录：分页 + 今日概况（呼入 / 已完成 / 转人工 / 未接 / 平均时长）。
     *
     * <p>只有电话渠道有通话记录；别的渠道调用会返回空列表（不用报错，页面只在电话详情里展示）。</p>
     */
    @GetMapping("/calls")
    public ApiResponse<CallPageVO> calls(
            @PathVariable Long channelId,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(adminService.calls(user, channelId, status, keyword, page, pageSize));
    }

    /**
     * 联调模拟器：按渠道类型跑一遍真实进线链路（身份带 sim_ 前缀，不真发推送/邮件）
     */
    @PostMapping("/simulate")
    public ApiResponse<ChannelSimulateVO> simulate(
            @PathVariable Long channelId,
            @RequestBody(required = false) SimulateBody body) {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(adminService.simulate(user, channelId, body));
    }
}
