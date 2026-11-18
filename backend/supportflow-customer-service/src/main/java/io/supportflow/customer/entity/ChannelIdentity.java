package io.supportflow.customer.entity;

import io.supportflow.common.config.JsonStringTypeHandler;
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
 * 外部身份与客户档案的绑定：App 用户 ID / 来电号码 / 发件邮箱。
 * 没有它，同一个 App 用户每次进来都会被当成新客户，工单与轨迹也串不起来。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "channel_identity", autoResultMap = true)
public class ChannelIdentity {
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;
    private String tenantCode;
    private Long channelId;
    private String identityType;
    private String externalId;
    private Long customerId;
    private String customerNo;
    private String customerName;
    private String sessionNo;
    /** 外部身份的附加信息：列是 {@code jsonb}，同样要走 JSON 类型处理器 */
    @TableField(value = "extra_json", typeHandler = JsonStringTypeHandler.class)
    private String extraJson;
    private Integer status;
    private LocalDateTime bindTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    @TableField("is_deleted")
    private Boolean deleted;
}
