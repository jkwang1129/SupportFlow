package io.supportflow.customer.controller.internal;

import io.supportflow.common.api.ApiResponse;
import io.supportflow.common.auth.CustomerInternalCredential.Caller;
import io.supportflow.customer.dto.internal.BroadcastBody;
import io.supportflow.customer.dto.internal.BroadcastResultVO;
import io.supportflow.customer.dto.internal.PublishBody;
import io.supportflow.customer.dto.internal.PublishResultVO;
import io.supportflow.customer.service.notification.NotificationBroadcastService;
import io.supportflow.customer.security.CustomerInternalCall;
import io.supportflow.customer.security.InternalCustomerEndpoint;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 通知写入（内部接口）：目前只有一处——平台公告广播。
 *
 * <p>广播 / 点名的业务逻辑在 {@link NotificationBroadcastService} 里，
 * 控制器只接参数、包响应。</p>
 */
@RestController
@InternalCustomerEndpoint(Caller.OPS)
@RequestMapping("/api/customer/internal/notifications")
public class InternalNotificationController {

    private final NotificationBroadcastService broadcastService;

    public InternalNotificationController(NotificationBroadcastService broadcastService) {
        this.broadcastService = broadcastService;
    }

    /**
     * 向全部租户广播一条通知（平台公告）。
     *
     * <p>返回实际推送到的租户数；租户清单拿不到时返回 0——公告本身已经发布成功，
     * 推送失败不该把发布动作带崩（发布记录在公告列表里，运营可以重发一次）。</p>
     */
    @PostMapping("/broadcast")
    @InternalCustomerEndpoint(value = Caller.OPS, tenantScoped = false)
    public ApiResponse<BroadcastResultVO> broadcast(@RequestBody BroadcastBody body) {
        return ApiResponse.ok(broadcastService.broadcast(body));
    }

    /**
     * 给指定租户发一条通知（导出审批结果这类只跟一家企业有关的消息）。
     *
     * <p>与广播分开：广播是"发给所有租户"，这里是"发给点名的这一家"，
     * 混一个接口进去迟早会有人传错参数把公告发给单个租户。</p>
     */
    @PostMapping("/publish")
    public ApiResponse<PublishResultVO> publish(@RequestAttribute(CustomerInternalCall.ATTRIBUTE) CustomerInternalCall call,
                                               @RequestBody PublishBody body) {
        return call.inTenant(body.tenantCode(), () -> ApiResponse.ok(broadcastService.publish(body)));
    }
}
