package io.supportflow.tenant.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 租户隔离视图（只读查询 + 网关用量上报的 UPSERT）。
 *
 * <p>列表/概览要一次带出"企业名 + 配额 + 今日用量 + 升级情况"，
 * 用一个 join 查询解决，避免平台页一屏几十次往返。</p>
 */
@Mapper
public interface TenantIsolationMapper {

    /** 租户列表（带配额、今日用量、升级与安全事件计数） */
    List<Map<String, Object>> selectTenantList(
            @Param("status") Integer status,
            @Param("isolationMode") Integer isolationMode,
            @Param("keyword") String keyword,
            @Param("limit") int limit,
            @Param("offset") long offset
    );

    /** 列表总数 */
    long countTenantList(
            @Param("status") Integer status,
            @Param("isolationMode") Integer isolationMode,
            @Param("keyword") String keyword
    );

    /**
     * 租户目录：编码 → 企业名称（不分页，只两列）。
     *
     * <p>给"跨租户列出数据"的平台页面做映射用：那些页面只存了 tenant_code，
     * 要显示企业名称就得有一份目录。走分页列表接口拿全量太笨——那边每页最多 100 条。</p>
     */
    List<Map<String, Object>> selectTenantNames();

    /** 平台概览：总数 / 活跃 / 独立库 / 冻结 / 落后版本 / 今日被限流 */
    Map<String, Object> selectOverview(@Param("latestVersion") int latestVersion);

    /** 单个租户详情（企业信息 + 配额 + 今日用量 + 库版本） */
    Map<String, Object> selectTenantDetail(@Param("tenantCode") String tenantCode);

    /** 升级历史 */
    List<Map<String, Object>> selectUpgradeHistory(
            @Param("tenantCode") String tenantCode,
            @Param("limit") int limit
    );

    /** 安全事件（租户为空则查全平台最近事件） */
    List<Map<String, Object>> selectSecurityEvents(
            @Param("tenantCode") String tenantCode,
            @Param("limit") int limit
    );

    /** 网关用量上报：同一天累加（幂等来自唯一键 + ON CONFLICT） */
    int upsertUsage(
            @Param("id") long id,
            @Param("tenantCode") String tenantCode,
            @Param("statDate") LocalDate statDate,
            @Param("requestCount") long requestCount,
            @Param("rejectedCount") long rejectedCount,
            @Param("sessionCount") long sessionCount,
            @Param("aiCallCount") long aiCallCount
    );
}
