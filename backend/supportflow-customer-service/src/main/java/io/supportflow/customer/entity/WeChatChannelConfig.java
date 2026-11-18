package io.supportflow.customer.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 微信公众号「原生接入」的渠道配置（一个公众号渠道一条）。
 *
 * <p>AppID / AppSecret / Token / EncodingAESKey 都是微信公众平台后台服务器配置里的项，
 * 缺任何一项都收不到消息；这里按渠道维度保存，回调按 AppID 反查。
 * 模板消息 ID 也放这里（物流提醒 / 工单进展 / 优惠券到账三类）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("channel_wechat_config")
public class WeChatChannelConfig {
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;
    private String tenantCode;
    private Long channelId;
    private String appId;
    private String appSecret;
    private String token;
    private String aesKey;
    private String welcomeText;
    private String templateLogistics;
    private String templateTicket;
    private String templateCoupon;
    /** 小程序订阅消息：工单进展模板 ID（与公众号模板消息不是一套） */
    private String subscribeTicket;
    /** 小程序订阅消息：客服接入模板 ID */
    private String subscribeService;
    /** 1-公众号 2-小程序（小程序的客服消息回调是 JSON 格式） */
    private Integer platform;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private String creator;
    private String editor;
    @TableField("is_deleted")
    private Boolean deleted;
}
