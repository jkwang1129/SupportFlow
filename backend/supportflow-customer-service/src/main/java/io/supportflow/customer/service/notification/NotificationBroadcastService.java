package io.supportflow.customer.service.notification;

import io.supportflow.customer.dto.internal.BroadcastBody;
import io.supportflow.customer.dto.internal.BroadcastResultVO;
import io.supportflow.customer.dto.internal.PublishBody;
import io.supportflow.customer.dto.internal.PublishResultVO;
import io.supportflow.customer.internal.TenantListClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 平台公告广播 / 点名发布（给 ops-service 内部调用）。
 *
 * <p>为什么由 customer-service 广播而不是 ops-service 逐个租户插库：消息表在 customer_db，
 * 写入逻辑（{@link NotificationService#publishToTenant}）也在这里；
 * 让控制面（ops）只发一条"要广播什么"，数据面自己负责"发给谁、怎么写"。</p>
 */
@Service
public class NotificationBroadcastService {

    private static final Logger log = LoggerFactory.getLogger(NotificationBroadcastService.class);

    /** 公告类型码（与 notification.notify_type 对齐） */
    private static final int NOTIFY_TYPE_ANNOUNCEMENT = 5;

    /** 点名发布不指定类型时的默认码（3=系统通知） */
    private static final int DEFAULT_NOTIFY_TYPE = 3;

    private final NotificationService notificationService;
    private final TenantListClient tenantListClient;

    public NotificationBroadcastService(NotificationService notificationService,
                                        TenantListClient tenantListClient) {
        this.notificationService = notificationService;
        this.tenantListClient = tenantListClient;
    }

    /**
     * 向全部租户广播一条通知（平台公告）。
     *
     * <p>返回实际推送到的租户数；租户清单拿不到时返回 0——公告本身已经发布成功，
     * 推送失败不该把发布动作带崩（发布记录在公告列表里，运营可以重发一次）。</p>
     */
    public BroadcastResultVO broadcast(BroadcastBody body) {
        if (body == null || body.title() == null || body.title().isBlank()) {
            return new BroadcastResultVO(0, null, "标题为空");
        }
        List<String> tenants = tenantListClient.allTenantCodes();
        int delivered = 0;
        for (String tenantCode : tenants) {
            try {
                notificationService.publishToTenant(tenantCode, NOTIFY_TYPE_ANNOUNCEMENT,
                        body.title(), body.content(), body.linkView());
                delivered++;
            } catch (Exception e) {
                log.warn("公告推送失败 tenant={} title={} error={}", tenantCode, body.title(), e.getMessage());
            }
        }
        log.info("平台公告已广播 title={} 目标租户={} 成功={}", body.title(), tenants.size(), delivered);
        return new BroadcastResultVO(delivered, tenants.size(), null);
    }

    /**
     * 给指定租户发一条通知（导出审批结果这类只跟一家企业有关的消息）。
     *
     * <p>与广播分开：广播是"发给所有租户"，这里是"发给点名的这一家"，
     * 混一个接口进去迟早会有人传错参数把公告发给单个租户。</p>
     */
    public PublishResultVO publish(PublishBody body) {
        if (body == null || body.tenantCode() == null || body.tenantCode().isBlank()
                || body.title() == null || body.title().isBlank()) {
            return new PublishResultVO(false, "租户或标题为空");
        }
        int notifyType = body.notifyType() == null ? DEFAULT_NOTIFY_TYPE : body.notifyType();
        notificationService.publishToTenant(body.tenantCode(), notifyType,
                body.title(), body.content(), body.linkView());
        log.info("租户通知已写入 tenant={} type={} title={}", body.tenantCode(), notifyType, body.title());
        return new PublishResultVO(true, null);
    }
}
