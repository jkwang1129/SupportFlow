package io.supportflow.customer.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 平台「AI 监控」的质量与安全聚合：AI 质检结果 + 会话实时质检预警。
 *
 * <p>只出聚合：多少条质检、平均分、风险多少、预警命中哪些规则。
 * 会话内容与会话号不出现——平台看的是"AI 判得准不准、风险拦没拦住"。</p>
 */
@Mapper
public interface PlatformAiQualityMapper {

    /** 质检总览：任务数、平均 AI 分、风险等级分布、人工复核数 */
    Map<String, Object> selectQaOverview(@Param("days") int days);

    /** 实时预警总览：命中数、严重数、待处理数 */
    Map<String, Object> selectAlertOverview(@Param("days") int days);

    /** 实时预警按天（趋势） */
    List<Map<String, Object>> selectAlertDaily(@Param("days") int days);

    /** 命中规则 Top（哪些规则最容易触发） */
    List<Map<String, Object>> selectTopRules(@Param("days") int days, @Param("limit") int limit);
}
