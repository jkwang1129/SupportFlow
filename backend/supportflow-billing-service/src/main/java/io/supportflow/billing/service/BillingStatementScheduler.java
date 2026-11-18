package io.supportflow.billing.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import io.supportflow.billing.dto.BatchGenerateVO;

/**
 * 自动出账：每月 1 号凌晨给所有活跃租户生成**上一个自然月**的账单。
 *
 * <p>为什么是上月而不是本月：账单要等账期跑完才有意义——本月还在产生用量，
 * 出了账也会被重算。所以每月 1 号结上一月，账期口径与
 * {@code BillingService.generateStatement} 的 "YYYY-MM" 完全一致。</p>
 *
 * <p>幂等与容错：重复执行是安全的（同账期已存在则重算，已结算的跳过并记录原因）；
 * tenant-service 拿不到时直接跳过本次，等下一轮（任务每天也兜底跑一次，见下）。</p>
 */
@Component
public class BillingStatementScheduler {

    private static final Logger log = LoggerFactory.getLogger(BillingStatementScheduler.class);

    private final BillingStatementBatchService batchService;

    public BillingStatementScheduler(BillingStatementBatchService batchService) {
        this.batchService = batchService;
    }

    /**
     * 每月 1 号 00:10 结上个月的账。
     *
     * <p>表达式可用 {@code supportflow.billing.statement-cron} 覆盖（默认值见 application.yml）。</p>
     */
    @Scheduled(cron = "${supportflow.billing.statement-cron:0 10 0 1 * ?}")
    public void generateLastMonthStatements() {
        String cycleKey = YearMonth.now().minusMonths(1).toString();
        log.info("月度出账任务开始：账期 {}", cycleKey);
        // operator 传 null：这是定时任务，"操作人"记成"系统"（审计里照样留痕）
        BatchGenerateVO result = batchService.generateForActiveTenants(null, cycleKey);
        if (result.tenants() == 0) {
            if (result.error() == null) {
                log.warn("月度出账跳过：平台里还没有「已开通且有套餐」的租户");
            } else {
                log.warn("月度出账跳过：{}", result.error());
            }
        }
    }
}
