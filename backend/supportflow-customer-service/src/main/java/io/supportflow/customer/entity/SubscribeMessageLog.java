package io.supportflow.customer.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * subscribe_message_log 小程序订阅消息发送日志。
 *
 * <p>为什么每次尝试都要落一条：订阅消息**失败是常态**——用户没订阅、一次性订阅次数用完、
 * 模板字段不符。没有这张表就只能看着"客户说没收到"发愣；有了它，企业能自己在配置页看到
 * 「发了 3 条，2 条失败，原因：用户没有订阅」。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("subscribe_message_log")
public class SubscribeMessageLog {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String tenantCode;

    private Long channelId;

    private String appId;

    private String openid;

    private Long customerId;

    /** 场景：1-工单进展、2-客服接入 */
    private Short scene;

    private String templateId;

    /** 业务号：工单号 / 会话号（与场景一起做幂等键） */
    private String bizNo;

    /** 结果：1-成功、2-失败、3-跳过 */
    private Short status;

    private Integer errcode;

    private String message;

    private String payloadJson;

    private LocalDateTime createTime;
}
