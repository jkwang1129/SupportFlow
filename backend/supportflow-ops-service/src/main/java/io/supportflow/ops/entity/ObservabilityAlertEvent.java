package io.supportflow.ops.entity;

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
 * observability_alert_event 告警事件。
 *
 * <p>事件是"某个时刻的事实"：某条规则在某一天、某个范围上越过了阈值。
 * 平台只能**确认或忽略**它，不能改数值——改了就不再是事实。
 * 同规则 + 同租户 + 同一天靠唯一索引保证只落一条，否则每轮扫描都会刷一条。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("observability_alert_event")
public class ObservabilityAlertEvent {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private Long ruleId;

    private String ruleName;

    private Short metric;

    private Short level;

    /** 按租户的规则才有值；全平台规则为空 */
    private String tenantCode;

    private BigDecimal metricValue;

    private BigDecimal threshold;

    private Short windowDays;

    /** 这条事件统计的日期（幂等键的一部分） */
    private LocalDate statDate;

    private String detail;

    /** 处置：1-待处理、2-已确认、3-已忽略 */
    private Short handleStatus;

    private String handleRemark;

    private String handlerName;

    private LocalDateTime handleTime;

    /** 是否已把消息推到企业消息中心 */
    private Boolean notified;

    private LocalDateTime eventTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
