package io.supportflow.customer.service.metrics;

import io.supportflow.common.context.TenantContext;
import io.supportflow.common.billing.UsageBatch;
import io.supportflow.customer.internal.TenantListClient;
import java.util.UUID;
import io.supportflow.customer.dto.metrics.Usage;
import io.supportflow.customer.internal.BillingUsageClient;
import io.supportflow.customer.mapper.AgentStatusMapper;
import io.supportflow.customer.mapper.FileMetaMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.supportflow.customer.entity.AgentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用量上报器：把 {@link BillingUsageCollector} 里的增量 + 席位/存储快照定期报给 billing。
 *
 * <p>两条设计说明：</p>
 * <ol>
 *   <li><b>稳定批次</b>：候选批次落入 Outbox 后，直到明确成功都沿用同一 ID 与业务日期；</li>
 *   <li><b>没变化就不报</b>：租户一旦产生过用量就会一直留在计数器的租户集合里，
 *       如果每分钟都无脑上报，500 个租户一天就是几十万次空写。
 *       所以只有「有增量」或「席位 / 存储快照变了」才发一次请求。</li>
 * </ol>
 *
 * <p>席位与存储是**快照值**（billing 侧按天取 GREATEST 保留峰值）：
 * 席位 = 该租户已启用的坐席数（agent_status 行数），存储 = file_meta 文件大小合计。</p>
 */
@Component
public class BillingUsageReporter {

    private static final Logger log = LoggerFactory.getLogger(BillingUsageReporter.class);

    private static final BigDecimal BYTES_PER_GB = BigDecimal.valueOf(1024L * 1024 * 1024);

    private final BillingUsageCollector collector;
    private final BillingUsageClient client;
    private final AgentStatusMapper agentStatusMapper;
    private final FileMetaMapper fileMetaMapper;
    private final BillingUsageOutbox outbox;
    private final TenantListClient tenants;
    private final Map<String, UsageBatch> prepared = new ConcurrentHashMap<>();

    /** 上一次上报的席位/存储快照，用来判断"没变化就不报" */
    private final Map<String, String> lastSnapshot = new ConcurrentHashMap<>();

    public BillingUsageReporter(BillingUsageCollector collector,
                                BillingUsageClient client,
                                AgentStatusMapper agentStatusMapper,
                                FileMetaMapper fileMetaMapper,
                                BillingUsageOutbox outbox, TenantListClient tenants) {
        this.collector = collector;
        this.client = client;
        this.agentStatusMapper = agentStatusMapper;
        this.fileMetaMapper = fileMetaMapper;
        this.outbox = outbox;
        this.tenants = tenants;
    }

    /** 默认 1 分钟上报一次；`supportflow.billing.usage-report-ms` 可调 */
    @Scheduled(fixedDelayString = "${supportflow.billing.usage-report-ms:60000}",
            initialDelayString = "${supportflow.billing.usage-report-initial-delay-ms:20000}")
    public synchronized void flush() {
        Set<String> tenantCodes = new HashSet<>(tenants.allTenantCodes());
        tenantCodes.addAll(collector.knownTenants());
        tenantCodes.addAll(prepared.keySet());
        for (String tenant : tenantCodes) {
            try {
                UsageBatch pending = outbox.pending(tenant);
                if (pending != null) {
                    if (!deliver(pending)) continue;
                }
                UsageBatch candidate = prepared.get(tenant);
                if (candidate == null) {
                    Snapshot snapshot = snapshotOf(tenant); // read before draining, so DB failure loses no counters
                    LocalDate today = LocalDate.now();
                    String key = snapshotKey(today, snapshot.seats(), snapshot.storageGb());
                    Usage usage = collector.drain(tenant);
                    if (usage.isEmpty() && key.equals(lastSnapshot.get(tenant))) continue;
                    candidate = new UsageBatch(UUID.randomUUID().toString(), tenant, today,
                            usage.sessions(), usage.messages(), usage.aiCalls(), Math.toIntExact(snapshot.seats()), snapshot.storageGb());
                    prepared.put(tenant, candidate);
                }
                // If commit outcome is unknown, retain exactly this candidate. Never restore it into
                // counters under a new ID: that could double count a successfully committed batch.
                if (!outbox.enqueue(candidate)) continue;
                prepared.remove(tenant, candidate);
                deliver(candidate);
            } catch (Exception e) {
                log.warn("用量批次保留待重试 tenant={} error={}", tenant, e.getClass().getSimpleName());
            }
        }
    }

    private boolean deliver(UsageBatch batch) {
        if (!client.report(batch)) return false;
        outbox.acknowledge(batch); // failed delete leaves the same batch durable for an idempotent retry
        prepared.remove(batch.tenantCode(), batch);
        lastSnapshot.put(batch.tenantCode(), snapshotKey(batch.statDate(), batch.seats(), batch.storageGb()));
        return true;
    }

    private static String snapshotKey(LocalDate day, long seats, BigDecimal storageGb) {
        return day + ":" + seats + ":" + storageGb.toPlainString();
    }

    /**
     * 席位与存储快照。
     *
     * <p>查询前显式设置租户上下文：这两张表都开了 RLS，虽然 SQL 里已经带了 `tenant_code` 条件，
     * 但把上下文也设上才是"两层都对"，不依赖"刚好写了 WHERE"。</p>
     *
     * <p>用 ScopedValue 绑定而不是 ThreadLocal set/还原：出了作用域自动失效，
     * 原来那段"先存 previous、完事再 set 回去"的代码整块不需要了——作用域外本来就没被改过。</p>
     */
    private Snapshot snapshotOf(String tenantCode) {
        try {
            return TenantContext.bind(tenantCode, () -> {
                Long seats = agentStatusMapper.selectCount(Wrappers.<AgentStatus>lambdaQuery()
                        .eq(AgentStatus::getTenantCode, tenantCode)
                        .eq(AgentStatus::getDeleted, false));
                long bytes = fileMetaMapper.sumSizeByTenant(tenantCode);
                BigDecimal gb = BigDecimal.valueOf(bytes)
                        .divide(BYTES_PER_GB, 2, RoundingMode.HALF_UP);
                return new Snapshot(seats == null ? 0 : seats, gb);
            });
        } catch (Exception e) {
            log.warn("采集席位/存储快照失败 tenant={} error={}", tenantCode, e.getMessage());
            throw new IllegalStateException("Cannot read metering snapshot", e);
        }
    }

    /** 席位（已启用坐席数）与存储（GB，两位小数） */
    private record Snapshot(long seats, BigDecimal storageGb) {
    }
}
