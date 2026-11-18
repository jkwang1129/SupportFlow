package io.supportflow.ops.mapper;

import io.supportflow.ops.entity.ObservabilityAlertRule;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** observability_alert_rule 告警规则 Mapper。 */
@Mapper
public interface ObservabilityAlertRuleMapper extends BaseMapper<ObservabilityAlertRule> {
}
