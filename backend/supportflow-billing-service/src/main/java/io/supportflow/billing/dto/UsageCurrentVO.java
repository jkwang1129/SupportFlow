package io.supportflow.billing.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 当前账期用量 vs 套餐配额（企业侧「套餐与用量」卡片、平台侧租户详情都用它）。
 *
 * @param usagePercent 四项配额里**最紧的那一项**的占用率——页面据此提示升级
 * @param daily        本账期按天的用量，给折线图用
 */
public record UsageCurrentVO(String cycleKey, Long sessions, Long messages, Long aiCalls,
                             Integer seats, BigDecimal storageGb,
                             Long planId, String planName, LocalDateTime planExpireTime,
                             Integer quotaSessions, Integer quotaAiCalls,
                             Integer quotaSeats, Integer quotaStorageGb,
                             int usagePercent, List<UsageDailyVO> daily) {
}
