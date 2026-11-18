package io.supportflow.customer.service.metrics;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import io.supportflow.customer.dto.metrics.Usage;

/**
 * 业务用量计数（内存增量）。
 *
 * <p>billing 的用量接口是**增量累加**语义（同一天重复上报只是把数字加上去），
 * 所以这里只维护「自上次上报以来的增量」，由 {@link BillingUsageReporter} 定期取走并清零。
 * 好处是请求链路上只有一次 {@link LongAdder} 自增，不会为了计量去写库、也不会拖慢会话链路。</p>
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
 * 内存增量足够，口径也与网关侧上报的用量保持一致（同样是内存累加 + 定时上报）。</p>
 */
@Component
public class BillingUsageCollector {

    /** 租户 → 增量计数器；租户数量级是「几百」，用 ConcurrentHashMap 足够 */
    private final ConcurrentHashMap<String, TenantCounters> counters = new ConcurrentHashMap<>();

    /** 客户进线开了一次会话 */
    public void countSession(String tenantCode) {
        countersOf(tenantCode).sessions.increment();
    }

    /** 落库了一条消息（客户 / 坐席 / 机器人） */
    public void countMessage(String tenantCode) {
        countersOf(tenantCode).messages.increment();
    }

    /** 调了一次 AI 能力（客服大脑 / 质检 / 检索 / 视觉） */
    public void countAiCall(String tenantCode) {
        countersOf(tenantCode).aiCalls.increment();
    }

    /**
     * 取走并清零所有增量。
     *
     * <p>先取走再上报：上报失败也不至于把同一段增量重复累加（宁可少算一次，
     * 也不能让一次网络抖动把账单算重）。</p>
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

        private final LongAdder sessions = new LongAdder();
        private final LongAdder messages = new LongAdder();
        private final LongAdder aiCalls = new LongAdder();

        private Usage drain() {
            // LongAdder.sumThenReset() 在并发自增下既不丢也不重复
            return new Usage(sessions.sumThenReset(), messages.sumThenReset(), aiCalls.sumThenReset());
        }
    }
}
