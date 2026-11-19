package io.supportflow.customer.service.metrics;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import io.supportflow.customer.dto.metrics.Usage;

/**
 * 业务用量计数（内存增量）。
 *
 * <p>这里只维护尚未组成批次的增量，由 {@link BillingUsageReporter} 定期取走，
 * 生成稳定 ID 后保存到 Outbox。原始计数阶段没有持久化保证。</p>
 *
 * <p>口径与 {@code UsageMeterService} 的说明一致：</p>
 * <ul>
 *   <li><b>会话数</b>：访客进线开一次算一次（会话是新建的才算，复用未结束的会话不重复计）；</li>
 *   <li><b>消息数</b>：客户 / 坐席 / 机器人落库的消息条数（去掉重发的重复消息）；</li>
 *   <li><b>AI 调用</b>：客服大脑、质检、知识库检索与问答、视觉识别各算一次。</li>
 * </ul>
 *
 * <p>已知取舍：进程重启会丢掉「还没上报的那一分钟」的增量。要做到不丢，
 * 得先把增量落到本地表再上报（outbox 模式）。本项目的用途是套餐超量提示与月度账单，
 * 原始内存增量不是持久化账本；形成并保存 Outbox 批次后才具备崩溃后的重报能力。</p>
 */
@Component
public class BillingUsageCollector {

    /** 租户 → 尚未形成持久化批次的增量计数器。 */
    private final ConcurrentHashMap<String, TenantCounters> counters = new ConcurrentHashMap<>();

    /** 客户进线开了一次会话 */
    public void countSession(String tenantCode) {
        countersOf(tenantCode).sessions.incrementAndGet();
    }

    /** 落库了一条消息（客户 / 坐席 / 机器人） */
    public void countMessage(String tenantCode) {
        countersOf(tenantCode).messages.incrementAndGet();
    }

    /** 调了一次 AI 能力（客服大脑 / 质检 / 检索 / 视觉） */
    public void countAiCall(String tenantCode) {
        countersOf(tenantCode).aiCalls.incrementAndGet();
    }

    /**
     * 取走并清零所有增量。
     *
     * <p>调用者必须保留已取出的数据直到批次入库，不能失败后换 ID 再形成同一份增量。</p>
     */
    public Map<String, Usage> drain() {
        Map<String, Usage> snapshot = new HashMap<>();
        for (Map.Entry<String, TenantCounters> entry : counters.entrySet()) {
            Usage usage = entry.getValue().drain();
            if (!usage.isEmpty()) {
                snapshot.put(entry.getKey(), usage);
            }
        }
        return snapshot;
    }

    /** 已经产生过用量的租户（上报时还要给它们刷新席位与存储快照） */
    public Set<String> knownTenants() {
        return Set.copyOf(counters.keySet());
    }

    public Usage drain(String tenantCode) {
        TenantCounters value = counters.get(tenantCode);
        return value == null ? new Usage(0, 0, 0) : value.drain();
    }

    private TenantCounters countersOf(String tenantCode) {
        if (tenantCode == null || tenantCode.isBlank()) {
            // 拿不到租户（未认证 / 内部任务）就不计数：用量必须挂在某个租户上才有意义
            return NOOP;
        }
        return counters.computeIfAbsent(tenantCode, key -> new TenantCounters());
    }

    /** 拿不到租户时用的空计数器，避免每个调用点都判空 */
    private static final TenantCounters NOOP = new TenantCounters();

    private static final class TenantCounters {

        private final AtomicLong sessions = new AtomicLong();
        private final AtomicLong messages = new AtomicLong();
        private final AtomicLong aiCalls = new AtomicLong();

        private Usage drain() {
            return new Usage(sessions.getAndSet(0), messages.getAndSet(0), aiCalls.getAndSet(0));
        }
    }
}
