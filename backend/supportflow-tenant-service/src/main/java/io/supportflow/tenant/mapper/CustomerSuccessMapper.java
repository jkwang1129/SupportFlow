package io.supportflow.tenant.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 客户成功（平台侧）的租户健康度聚合。

 * <p>健康度是"算出来的"，不是让运营手工填的——数据全在 tenant_db 自己的库里：
 * {@code tenant}（到期时间、套餐、状态）+ {@code tenant_usage_daily}（网关按天上报的用量）
 * + {@code tenant_quota}（配额上限）。人工只维护"谁负责、什么时候回访"，
 * 那部分在 ops_db.customer_success（见 ops-service）。</p>
 *
 * <p>只出聚合与评分依据，不出客户档案与会话内容——平台看的是"这家租户还健康吗"。</p>
 */
@Mapper
public interface CustomerSuccessMapper {

    /** 每个租户一行：到期、套餐、配额、近 30 天用量、活跃天数、前后 7 天趋势 */
    List<Map<String, Object>> selectTenantHealth();

    /** 单个租户的健康度明细（点开某行时用） */
    Map<String, Object> selectTenantHealthDetail(@Param("tenantCode") String tenantCode);
}
