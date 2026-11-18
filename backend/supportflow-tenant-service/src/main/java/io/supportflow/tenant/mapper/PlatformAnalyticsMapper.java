package io.supportflow.tenant.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 平台经营分析（平台数据页）的聚合查询。
 *
 * <p>只出**聚合数字**：租户数量、套餐分布、用量汇总。客户档案、会话内容、工单明细
 * 这些"看得见某个企业具体在干什么"的数据不从这里出——平台侧只做经营视角的统计。</p>
 */
@Mapper
public interface PlatformAnalyticsMapper {

    /** 租户总览：总数 / 状态分布 / 付费租户数（非免费版）/ 本月新增 */
    Map<String, Object> selectTenantOverview(@Param("freePlanId") long freePlanId);

    /** 本月用量汇总（网关上报的 tenant_usage_daily） */
    Map<String, Object> selectMonthUsage();

    /** 近 N 个月每月新增租户（没有新增的月份不会出现在结果里，由 Java 补零） */
    List<Map<String, Object>> selectTenantGrowth(@Param("months") int months);

    /** 套餐分布：当前各套餐挂着多少租户（plan_id 为空表示还没开通套餐） */
    List<Map<String, Object>> selectPlanDistribution();

    /** 状态分布 */
    List<Map<String, Object>> selectStatusDistribution();

    /** 本月用量 Top 租户 */
    List<Map<String, Object>> selectTopTenants(@Param("limit") int limit);

    /** 租户列表（分页）：企业 + 套餐 + 状态 + 本月用量 */
    List<Map<String, Object>> selectTenantPage(@Param("limit") int limit, @Param("offset") long offset,
                                               @Param("keyword") String keyword, @Param("status") Integer status);

    /** 租户列表总数（与上面同一套过滤条件） */
    long countTenantPage(@Param("keyword") String keyword, @Param("status") Integer status);
}
