package io.supportflow.ops.service;

import io.supportflow.ops.entity.ObservabilityAlertEvent;
import io.supportflow.ops.internal.AiMetricsClient;
import io.supportflow.ops.internal.CustomerNotificationClient;
import io.supportflow.ops.mapper.ObservabilityAlertEventMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 告警评估定时任务：判阈值 + 把该通知的推给企业。
 *
 * <p>两段分开：{@link ObservabilityService#evaluate} 只负责"判断与落库"（可被手动触发、
 * 也可被测试直接调），通知是它的下游——**落库失败不该影响别的规则，通知失败也不该影响落库**，
 * 没推成功的事件下一轮还会被捡起来（{@code notified = false}）。</p>
 *
 * <p>间隔默认 5 分钟：告警的价值在"及时"，但每分钟扫一遍 AI 大盘纯属浪费；
 * 真要更快可以改 {@code supportflow.observability.evaluate-ms}。</p>
 */
@Component
public class ObservabilityEvaluator {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityEvaluator.class);

    /** 一次最多推多少条通知：告警风暴时也要有个上限 */
    private static final int NOTIFY_LIMIT = 50;

    private final ObservabilityService service;
    private final AiMetricsClient metricsClient;
    private final ObservabilityAlertEventMapper eventMapper;
    private final CustomerNotificationClient notificationClient;

    public ObservabilityEvaluator(ObservabilityService service,
                                  AiMetricsClient metricsClient,
                                  ObservabilityAlertEventMapper eventMapper,
                                  CustomerNotificationClient notificationClient) {
        this.service = service;
        this.metricsClient = metricsClient;
        this.eventMapper = eventMapper;
        this.notificationClient = notificationClient;
    }

    @Scheduled(fixedDelayString = "${supportflow.observability.evaluate-ms:300000}",
            initialDelayString = "${supportflow.observability.initial-delay-ms:60000}")
    public void evaluate() {
        try {
            int[] result = runOnce();
            int created = result[0];
            int notified = result[1];
            if (created > 0 || notified > 0) {
                log.info("可观测评估完成：新增告警 {} 条，通知企业 {} 条", created, notified);
            }
        } catch (Exception e) {
            // 定时任务抛异常会被吞掉且下一轮照跑，这里至少留下可查的一句
            log.warn("可观测评估失败：{}", e.getMessage());
        }
    }

    /**
     * 跑一轮完整评估（判断 + 通知）。
     *
     * <p>定时任务与页面上的「立即评估」共用这一条路径：**两边口径必须一致**，
     * 否则会出现"手动跑有告警、定时跑没通知"这种对不上的现象。</p>
     *
     * @return [新增事件数, 通知企业条数]
     */
    public int[] runOnce() {
        int created = service.evaluate(metricsClient);
        int notified = notifyTenants();
        return new int[]{created, notified};
    }

    /** 把「按租户」且还没通知过的事件推给对应企业的消息中心 */
    public int notifyTenants() {
        List<ObservabilityAlertEvent> pending = eventMapper.selectList(
                Wrappers.<ObservabilityAlertEvent>lambdaQuery()
                        .eq(ObservabilityAlertEvent::getNotified, false)
                        .isNotNull(ObservabilityAlertEvent::getTenantCode)
                        .orderByAsc(ObservabilityAlertEvent::getEventTime)
                        .last("LIMIT " + NOTIFY_LIMIT));
        int sent = 0;
        for (ObservabilityAlertEvent event : pending) {
            String level = ObservabilityService.levelText(event.getLevel());
            String title = "AI " + ("严重".equals(level) ? "成本/稳定性告警" : "用量提醒") + " · " + level;
            String content = event.getDetail() == null ? event.getRuleName()
                    : event.getRuleName() + "：" + event.getDetail();
            // 跳「版本与升级」：成本/用量告警的下一步通常就是看套餐与配额，这个落点最有用
            boolean ok = notificationClient.publish(event.getTenantCode(), 3, title, content, "upgrade");
            if (ok) {
                service.markNotified(event.getId());
                sent++;
            }
        }
        return sent;
    }
}
