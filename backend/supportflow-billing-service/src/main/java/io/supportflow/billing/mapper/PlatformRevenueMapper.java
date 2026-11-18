package io.supportflow.billing.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 平台收入统计（平台数据页）的聚合查询。
 *
 * <p>口径写死一句话：**收入 = 已支付订单（status=2 且有支付时间）的金额**。
 * 账期账单（后付费的超量费）单独一组数字摆着，不混进"实收"——否则
 * "收了多少钱"这件事会被两张表各算一半，对不上账。</p>
 */
@Mapper
public interface PlatformRevenueMapper {

    /** 收入总览：累计实收 / 本月实收 / 待支付订单 */
    Map<String, Object> selectRevenueOverview(@Param("cycleKey") String cycleKey);

    /** 近 N 个月实收趋势（没有收入的月份由 Java 补零） */
    List<Map<String, Object>> selectRevenueTrend(@Param("months") int months);

    /** 按套餐的累计实收 */
    List<Map<String, Object>> selectPlanRevenue();

    /** 按支付方式的累计实收 */
    List<Map<String, Object>> selectPayMethodRevenue();

    /** 账期账单全周期汇总（后付费口径，与上面的预付费分开摆） */
    Map<String, Object> selectStatementSummary();
}
