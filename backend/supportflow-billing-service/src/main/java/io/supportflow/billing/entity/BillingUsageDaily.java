package io.supportflow.billing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * billing_usage_daily：按天的用量事实表（计量）。
 * 数据来源：customer-service 的会话/消息统计 + 网关的请求统计 + 席位/存储快照，
 * 由计量上报接口写入（幂等累加）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("billing_usage_daily")
public class BillingUsageDaily {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String tenantCode;

    private LocalDate statDate;

    /** 会话数（机器人 + 人工） */
    private Long sessions;

    /** 消息数 */
    private Long messages;

    /** AI 调用次数（大脑 / 质检 / 检索 / 视觉） */
    private Long aiCalls;

    /** 坐席数快照（当天峰值） */
    private Integer seats;

    /** 存储快照（GB） */
    private BigDecimal storageGb;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
