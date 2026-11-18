package io.supportflow.billing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * plan 套餐表：套餐是"配额 + 价格"的唯一来源。
 * 下单按它的价格算钱；租户的限流配额（tenant_quota）也按它下发——
 * 这样"买了多少钱的套餐"和"网关放你多少 QPS"不会两处口径。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("plan")
public class Plan {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 套餐编码：STANDARD / PROFESSIONAL / ENTERPRISE */
    private String planCode;

    private String planName;

    private BigDecimal priceYearly;

    private BigDecimal priceMonthly;

    /** 月机器人会话配额 */
    private Integer quotaRobotSessions;

    /** 渠道数配额 */
    private Integer quotaChannels;

    /** 是否含全量 AI 质检 */
    private Boolean quotaAiQa;

    /** 是否含视觉识别 */
    private Boolean quotaVision;

    /** 坐席数配额（0 = 不限） */
    private Integer quotaSeats;

    /** 存储配额（GB，0 = 不限） */
    private Integer quotaStorageGb;

    /** 每月 AI 调用配额（0 = 不限） */
    private Integer quotaAiCalls;

    /** 超量单价：每 1000 次会话多少钱（0 = 不允许超量） */
    private BigDecimal overagePricePerK;

    private Boolean isEnabled;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private String creator;

    private String editor;

    @TableField("is_deleted")
    private Boolean deleted;
}
