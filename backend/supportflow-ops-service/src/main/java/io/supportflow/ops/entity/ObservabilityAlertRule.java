package io.supportflow.ops.entity;

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
 * observability_alert_rule 告警规则。
 *
 * <p>规则是"平台的判断标准"：哪个指标、看几天的窗口、超过多少算异常、什么级别、
 * 只看全平台还是逐个租户看。**阈值必须能说清是针对什么口径的**
 * （失败率是百分比、耗时是毫秒、成本是元、调用量与 token 是次数），
 * 所以指标用枚举码而不是自由文本。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("observability_alert_rule")
public class ObservabilityAlertRule {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String ruleName;

    /** 指标：1-失败率%、2-平均耗时ms、3-单日成本元、4-单日调用量、5-单日 Token */
    private Short metric;

    /** 统计窗口（天），1 = 只看今天 */
    private Short windowDays;

    /** 比较符：1-大于等于、2-小于等于 */
    private Short compareOp;

    private BigDecimal threshold;

    /** 级别：1-提示、2-警告、3-严重 */
    private Short level;

    /** 范围：1-全平台、2-按租户 */
    private Short scope;

    /** 按租户的规则是否把消息推到该企业的消息中心 */
    private Boolean notifyTenant;

    private Boolean isEnabled;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private String creator;

    private String editor;

    @TableField("is_deleted")
    private Boolean deleted;
}
