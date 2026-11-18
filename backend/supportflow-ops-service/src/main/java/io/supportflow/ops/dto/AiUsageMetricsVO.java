package io.supportflow.ops.dto;

import java.util.List;

/**
 * supportflow-ai 的用量大盘原样结构（客户端边界上的接收实体）。
 *
 * <p>这份 schema 归 Python 侧所有；以前 ops 直接透传 {@code Map}，字段改了 Java 这边
 * 毫无感知。现在在客户端边界落成实体——对方加字段不会被丢弃（Jackson 默认忽略未知字段，
 * 这里也接受这个取舍），但**删字段/改名会让这里反序列化失败或字段为 null**，
 * 比"页面某个数字悄悄变 0"要容易发现。</p>
 */
public record AiUsageMetricsVO(Integer days, String from, String to, AiUsageOverviewVO overview,
                               List<AiUsageDailyVO> daily, List<AiUsageModelVO> byModel,
                               List<AiUsageSceneVO> byScene, List<AiUsageTenantVO> topTenants,
                               String lastRealCallAt) {
}
