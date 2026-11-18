package io.supportflow.customer.nativechannel;

import io.supportflow.common.auth.LoginUser;
import io.supportflow.common.exception.BizException;
import io.supportflow.customer.dto.channel.CallPageVO;
import io.supportflow.customer.dto.channel.ChannelNativeDetailVO;
import io.supportflow.customer.dto.channel.ChannelSimulateVO;
import io.supportflow.customer.dto.channel.ConfiguredVO;
import io.supportflow.customer.dto.channel.MailInbound;
import io.supportflow.customer.dto.channel.PhoneEvent;
import io.supportflow.customer.dto.channel.SimulateBody;
import io.supportflow.customer.dto.channel.SimulateStepVO;
import io.supportflow.customer.dto.channel.SimulatorHintVO;
import io.supportflow.customer.entity.Channel;
import io.supportflow.customer.mapper.ChannelMapper;
import io.supportflow.customer.service.phone.PhoneCallService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 原生渠道（App / 400 热线 / 邮件）的后台配置与联调：渠道归属校验、配置存取、通话记录、模拟器。
 *
 * <p>字段定义由 {@link ChannelNativeConfigService#fields(int)} 给，前端按定义渲染表单，
 * 所以三种渠道共用一个页面；联调模拟器复用真实进线链路，只是把身份标成 {@code sim_} 前缀不真发外呼。</p>
 */
@Service
public class ChannelNativeAdminService {

    private static final Logger log = LoggerFactory.getLogger(ChannelNativeAdminService.class);

    private final ChannelMapper channelMapper;
    private final ChannelNativeConfigService configService;
    private final ChannelNativeInboundService inboundService;
    /** 400 通话记录（列表 + 今日概况） */
    private final PhoneCallService phoneCallService;

    public ChannelNativeAdminService(ChannelMapper channelMapper, ChannelNativeConfigService configService,
                                     ChannelNativeInboundService inboundService,
                                     PhoneCallService phoneCallService) {
        this.channelMapper = channelMapper;
        this.configService = configService;
        this.inboundService = inboundService;
        this.phoneCallService = phoneCallService;
    }

    /** 原生渠道详情：配置回显（密钥掩码）+ 要填的字段 + 回调地址 + 模拟器提示。 */
    public ChannelNativeDetailVO detail(LoginUser user, Long channelId) {
        Channel channel = requireChannel(user, channelId);
        return new ChannelNativeDetailVO(channel.getChannelType(), channel.getName(),
                configService.fields(channel.getChannelType()),
                configService.displayValues(user.tenantCode(), channelId),
                configService.row(user.tenantCode(), channelId) != null,
                configService.webhookUrls(channel.getChannelType(), configService.appKeyOf(channelId)),
                simulatorHint(channel.getChannelType()));
    }

    /**
     * 保存原生渠道配置（字段定义由渠道类型决定，服务端按 schema 校验）。
     *
     * <p>入参是「字段名 → 值」的表单数据：App / 邮件 / 400 热线三套字段完全不同，
     * 清单维护在 {@link ChannelNativeConfigService} 的 SCHEMA 里（详情接口回的就是它，
     * 前端照着渲染表单，新增字段只要加一行）。用 Map 是刻意的——写成实体等于把 schema 抄第二遍，
     * 且以后加字段必须发版；服务端仍会按 schema 逐个校验必填 / 类型 / 下拉取值。</p>
     */
    public ConfiguredVO save(LoginUser user, Long channelId, Map<String, Object> body) {
        requireChannel(user, channelId);
        configService.save(user.tenantCode(), channelId, body, user.name());
        log.info("原生渠道配置已保存 tenant={} channelId={} 操作人={}",
                user.tenantCode(), channelId, user.name());
        return new ConfiguredVO(true);
    }

    /**
     * 400 通话记录：分页 + 今日概况（呼入 / 已完成 / 转人工 / 未接 / 平均时长）。
     *
     * <p>只有电话渠道有通话记录；别的渠道调用会返回空列表（不用报错，页面只在电话详情里展示）。</p>
     */
    public CallPageVO calls(LoginUser user, Long channelId, Integer status, String keyword,
                            int page, int pageSize) {
        requireChannel(user, channelId);
        return phoneCallService.page(user.tenantCode(), channelId, status, keyword,
                Math.max(1, page), Math.min(Math.max(1, pageSize), 100));
    }

    /** 联调模拟器：按渠道类型跑一遍真实进线链路（身份带 sim_ 前缀，不真发推送/邮件）。 */
    public ChannelSimulateVO simulate(LoginUser user, Long channelId, SimulateBody body) {
        Channel channel = requireChannel(user, channelId);
        if (configService.row(user.tenantCode(), channelId) == null) {
            throw new BizException(40001, "请先保存该渠道的原生接入配置，再跑联调");
        }
        String content = body == null || body.content() == null || body.content().isBlank()
                ? "退货多久到账？" : body.content().trim();
        String stamp = String.valueOf(System.currentTimeMillis());
        List<SimulateStepVO> steps = new ArrayList<>();
        switch (channel.getChannelType()) {
            case 4 -> steps.add(step("App 用户发消息", inboundService.appMessage(channel,
                    "sim_app_user_" + stamp, "模拟 App 用户", content, "sim-msg-" + stamp)));
            case 6 -> {
                String subject = body != null && body.subject() != null && !body.subject().isBlank()
                        ? body.subject().trim() : "发票抬头修改为：杭州SupportFlow";
                steps.add(step("邮件网关推送新邮件", inboundService.mailInbound(channel,
                        new MailInbound("sim_" + stamp + "@example.com", "模拟客户",
                                subject, content, "sim-mail-" + stamp))));
            }
            case 5 -> {
                String callId = "sim-call-" + stamp;
                steps.add(step("客户呼入", inboundService.phoneEvent(channel,
                        new PhoneEvent("incoming", callId,
                                "13800000000", null, null, null, null, null, null, null))));
                steps.add(step("客户按键 1（订单查询）", inboundService.phoneEvent(channel,
                        new PhoneEvent("dtmf", callId,
                                "13800000000", "1", null, null, null, null, null, null))));
                steps.add(step("坐席接听", inboundService.phoneEvent(channel,
                        new PhoneEvent("answered", callId,
                                "13800000000", null, null, null, null, null, "苏三", null))));
                steps.add(step("话务侧回传录音", inboundService.phoneEvent(channel,
                        new PhoneEvent("recording", callId,
                                "13800000000", null, null, null, null,
                                "https://example.com/recording/sim-" + stamp + ".mp3", null, null))));
                steps.add(step("客户挂机（自动小结 + 建单）", inboundService.phoneEvent(channel,
                        new PhoneEvent("hangup", callId,
                                "13800000000", null, "订单查询", "人工坐席已答复", "01:12",
                                null, null, null))));
                steps.add(step("另一通未接来电（只留记录、不建单）", inboundService.phoneEvent(channel,
                        new PhoneEvent("no_answer", "sim-call-missed-" + stamp,
                                "13800000001", null, null, null, null, null, null, "无人接听"))));
            }
            default -> throw new BizException(40001, "这个渠道类型没有原生联调模拟器");
        }
        return new ChannelSimulateVO(channel.getChannelType(), steps,
                "模拟身份（sim_ 前缀）不会真的推送到 App / 发邮件出去；"
                        + "会话与工单都是真实的，可以在「在线客服」和「工单中心」看到");
    }

    /** 渠道归属校验：必须属于当前登录租户且未删除，否则当作不存在（不泄露别家渠道信息）。 */
    private Channel requireChannel(LoginUser user, Long channelId) {
        Channel channel = channelMapper.selectById(channelId);
        if (channel == null || Boolean.TRUE.equals(channel.getDeleted())
                || !user.tenantCode().equals(channel.getTenantCode())) {
            throw new BizException(40401, "渠道不存在");
        }
        return channel;
    }

    private static SimulateStepVO step(String name, Object result) {
        return new SimulateStepVO(name, result);
    }

    private static SimulatorHintVO simulatorHint(int channelType) {
        return switch (channelType) {
            case 4 -> new SimulatorHintVO("模拟一条 App 用户消息，走完「绑定身份 → 开会话 → 落库 → 机器人接待」",
                    "退货多久到账？");
            case 6 -> new SimulatorHintVO("模拟一封客户邮件，走完「建工单 → 自动回执 → 解析诉求」",
                    "发票抬头改成杭州SupportFlow，税号 91330100MA2XXXXXXX");
            case 5 -> new SimulatorHintVO("模拟一通来电：呼入 → 按键 1 → 挂机，走完「语音导航 → 转人工/播报 → 通话小结建单」",
                    "（电话渠道不需要输入内容）");
            default -> new SimulatorHintVO("该渠道类型没有模拟器", "");
        };
    }
}
