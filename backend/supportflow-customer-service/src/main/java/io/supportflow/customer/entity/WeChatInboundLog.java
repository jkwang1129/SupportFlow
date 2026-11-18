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
 * 微信上行消息去重日志。
 *
 * <p>微信 5 秒内没收到 success 就会重试，同一条消息可能推好几次；
 * 这里按 (app_id, msg_id) 唯一约束去重，避免一次提问在会话里出现两条。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("wechat_inbound_log")
public class WeChatInboundLog {
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;
    private String tenantCode;
    private String appId;
    private String msgId;
    private String openid;
    private String msgType;
    private String eventType;
    private LocalDateTime createTime;
    @TableField("is_deleted")
    private Boolean deleted;
}
