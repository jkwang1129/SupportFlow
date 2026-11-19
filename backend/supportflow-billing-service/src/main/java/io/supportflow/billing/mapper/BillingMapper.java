package io.supportflow.billing.mapper;

import io.supportflow.billing.dto.PlanDistributionVO;
import io.supportflow.billing.dto.UsageDailyVO;
import io.supportflow.billing.entity.BillingStatement;
import io.supportflow.billing.entity.BillingUsageDaily;
import io.supportflow.billing.entity.BillingOrder;
import io.supportflow.billing.entity.Plan;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 计费域 Mapper：套餐 / 用量 / 账单的聚合查询与幂等累加。 */
@Mapper
public interface BillingMapper {

    String insertUsageBatch(@Param("batch") io.supportflow.common.billing.UsageBatch batch,
                            @Param("fingerprint") String fingerprint);

    String usageBatchFingerprint(@Param("tenantCode") String tenantCode, @Param("batchId") String batchId);

    // ------------------------------------------------------------------ 用量计量

    /** 按天累加用量（同一租户同一天 UPSERT，重复上报不会写重） */
    int upsertUsage(@Param("id") long id,
                    @Param("tenantCode") String tenantCode,
                    @Param("statDate") LocalDate statDate,
                    @Param("sessions") long sessions,
                    @Param("messages") long messages,
                    @Param("aiCalls") long aiCalls,
                    @Param("seats") int seats,
                    @Param("storageGb") BigDecimal storageGb);

    /** 账期内的用量合计（账单与"当前用量"都用它） */
    Map<String, Object> sumUsage(@Param("tenantCode") String tenantCode,
                                 @Param("from") LocalDate from,
                                 @Param("to") LocalDate to);

    /** 按天用量明细（企业侧"用量趋势"） */
    List<Map<String, Object>> selectUsageDaily(@Param("tenantCode") String tenantCode,
                                               @Param("from") LocalDate from,
                                               @Param("to") LocalDate to);

    /** 坐席数快照：取最近一次上报的值（网关/计量上报会带当天峰值） */
    Integer latestSeats(@Param("tenantCode") String tenantCode);

    // ------------------------------------------------------------------ 账单

    List<BillingStatement> selectStatements(@Param("tenantCode") String tenantCode,
                                            @Param("cycleKey") String cycleKey,
                                            @Param("status") Integer status,
                                            @Param("limit") int limit,
                                            @Param("offset") long offset);

    long countStatements(@Param("tenantCode") String tenantCode,
                         @Param("cycleKey") String cycleKey,
                         @Param("status") Integer status);

    /** 平台概览：在收 / 已收 / 逾期 / 本月账单数 */
    Map<String, Object> selectStatementOverview(@Param("cycleKey") String cycleKey);

    /**
     * 平台概览：本账期**真实收到的钱**（来自已支付订单，不是账单）。
     *
     * <p>为什么单独一个查询：预付费走订单（买套餐一次性收款），后付费走账单（超量费）。
     * 只统计账单的话，一个年付 ¥5,980 的租户在平台概览里会显示"已收 0"。</p>
     */
    Map<String, Object> selectPaymentOverview(@Param("cycleKey") String cycleKey);

    /** 套餐分布（平台侧）：每个套餐多少租户在用 —— 租户表在 tenant_db，这里按账单快照统计 */
    List<Map<String, Object>> selectPlanDistribution(@Param("cycleKey") String cycleKey);

    /** 已启用套餐（企业侧展示） */
    List<Plan> selectPlans();

    // ------------------------------------------------------------------ 订单 / 收款流水
    //
    // 三个查询共用 XML 里同一段 <sql id="orderFilters">：列表、计数、金额合计的口径必须一模一样，
    // 否则会出现"列表 3 条、合计按 5 条算"这种对不上账的情况。
    // 也没有用 MyBatis-Plus 的 selectCount：它会把 wrapper 上的 order by 带进 SQL，
    // Postgres 直接报 "column ... must appear in the GROUP BY clause"（踩过这个坑）。

    List<BillingOrder> selectOrders(@Param("tenantCode") String tenantCode,
                                    @Param("status") Integer status,
                                    @Param("planId") Long planId,
                                    @Param("payCycle") Integer payCycle,
                                    @Param("payMethod") Integer payMethod,
                                    @Param("cycleKey") String cycleKey,
                                    @Param("payFrom") String payFrom,
                                    @Param("payTo") String payTo,
                                    @Param("keyword") String keyword,
                                    @Param("limit") int limit,
                                    @Param("offset") long offset);

    long countOrders(@Param("tenantCode") String tenantCode,
                     @Param("status") Integer status,
                     @Param("planId") Long planId,
                     @Param("payCycle") Integer payCycle,
                     @Param("payMethod") Integer payMethod,
                     @Param("cycleKey") String cycleKey,
                     @Param("payFrom") String payFrom,
                     @Param("payTo") String payTo,
                     @Param("keyword") String keyword);

    /** 筛选结果的收款合计（财务对账要看"共几笔、多少钱"） */
    BigDecimal sumOrders(@Param("tenantCode") String tenantCode,
                         @Param("status") Integer status,
                         @Param("planId") Long planId,
                         @Param("payCycle") Integer payCycle,
                         @Param("payMethod") Integer payMethod,
                         @Param("cycleKey") String cycleKey,
                         @Param("payFrom") String payFrom,
                         @Param("payTo") String payTo,
                         @Param("keyword") String keyword);
}
