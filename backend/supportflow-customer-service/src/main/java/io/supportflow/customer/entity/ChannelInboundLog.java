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

/** 原生渠道入站去重：邮件 Message-ID、电话 callId+事件、App clientMsgNo 都会重推。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("channel_inbound_log")
public class ChannelInboundLog {
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;
    private String tenantCode;
    private Long channelId;
    private String identityType;
    private String dedupeKey;
    private String eventType;
    private LocalDateTime createTime;
    @TableField("is_deleted")
    private Boolean deleted;
}
