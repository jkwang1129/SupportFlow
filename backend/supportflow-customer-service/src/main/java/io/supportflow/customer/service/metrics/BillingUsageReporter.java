package io.supportflow.customer.service.metrics;

import io.supportflow.common.context.TenantContext;
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
 *   <li><b>先取走再上报</b>：{@code drain()} 已经清零，网络抖动最多少算一次增量，
 *       不会因为重试把同一段用量算两遍（账单宁可少算，不能多算）；</li>
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

    /** 上一次上报的席位/存储快照，用来判断"没变化就不报" */
    private final Map<String, String> lastSnapshot = new ConcurrentHashMap<>();

    public BillingUsageReporter(BillingUsageCollector collector,
                                BillingUsageClient client,
                                AgentStatusMapper agentStatusMapper,
                                FileMetaMapper fileMetaMapper) {
        this.collector = collector;
        this.client = client;
        this.agentStatusMapper = agentStatusMapper;
        this.fileMetaMapper = fileMetaMapper;
    }

    /** 默认 1 分钟上报一次；`supportflow.billing.usage-report-ms` 可调 */
    @Scheduled(fixedDelayString = "${supportflow.billing.usage-report-ms:60000}",
            initialDelayString = "${supportflow.billing.usage-report-initial-delay-ms:20000}")
    public void flush() {
        Map<String, Usage> drained = collector.drain();
        Set<String> tenants = new HashSet<>(collector.knownTenants());
        tenants.addAll(drained.keySet());
        if (tenants.isEmpty()) {
            return;
        }
        LocalDate today = LocalDate.now();
        int sent = 0;
        for (String tenant : tenants) {
            Usage usage = drained.getOrDefault(
                    tenant, new Usage(0, 0, 0));
            Snapshot snapshot = snapshotOf(tenant);
            String key = snapshot.seats() + ":" + snapshot.storageGb().toPlainString();
            boolean snapshotChanged = !key.equals(lastSnapshot.get(tenant));
            if (usage.sessions() == 0 && usage.messages() == 0 && usage.aiCalls() == 0 && !snapshotChanged) {
                continue;
            }
            if (client.report(tenant, today, usage.sessions(), usage.messages(),
                    usage.aiCalls(), snapshot.seats(), snapshot.storageGb())) {
                lastSnapshot.put(tenant, key);
                sent++;
            }
        }
        if (sent > 0) {
            log.debug("用量已上报 {} 个租户", sent);
        }
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
            return new Snapshot(0, BigDecimal.ZERO);
        }
    }

    /** 席位（已启用坐席数）与存储（GB，两位小数） */
    private record Snapshot(long seats, BigDecimal storageGb) {
    }
}
