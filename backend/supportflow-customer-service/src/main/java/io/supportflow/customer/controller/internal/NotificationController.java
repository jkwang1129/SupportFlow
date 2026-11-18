package io.supportflow.customer.controller.internal;

import io.supportflow.common.context.LoginUserContext;
import io.supportflow.common.api.ApiResponse;
import io.supportflow.common.api.CountVO;
import io.supportflow.common.auth.LoginUser;
import io.supportflow.customer.dto.notification.NotificationPageVO;
import io.supportflow.customer.dto.notification.NotificationSummaryVO;
import io.supportflow.customer.dto.notification.NotificationVO;
import io.supportflow.customer.service.notification.NotificationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 消息中心：右上角铃铛点开的那一栏。
 *
 * <p>企业级消息（工单 SLA 预警）+ 发给我的消息（分派给我的工单）一起返回，
 * 每条带"我读没读"；已读是写在 notification_read 里，不影响别人。</p>
 */
@RestController
@RequestMapping("/api/customer/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /**
     * 铃铛抽屉用的最近一屏通知（不分页，与下面的 /page 并存）。
     */
    @GetMapping
    public ApiResponse<List<NotificationVO>> list(
            @RequestParam(required = false) Integer notifyType,
            @RequestParam(required = false) Integer limit
    ) {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(notificationService.list(user, notifyType, limit));
    }

    /**
     * 消息中心页（带分页）：关键字搜标题与正文，可选只看未读。
     *
     * <p>与上面的 {@code /} 并存：铃铛抽屉只要"最近 50 条"，页面要的是分页 + 总数。</p>
     */
    @GetMapping("/page")
    public ApiResponse<NotificationPageVO> page(
            @RequestParam(required = false) Integer notifyType,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean unreadOnly
    ) {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(notificationService.page(user, notifyType, page, pageSize, keyword, unreadOnly));
    }

    /**
     * 消息中心左侧的分类统计（每类多少条 / 多少未读）
     */
    @GetMapping("/summary")
    public ApiResponse<NotificationSummaryVO> summary() {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(notificationService.summary(user));
    }

    /**
     * 未读数（左侧菜单与铃铛角标用）。
     */
    @GetMapping("/unread-count")
    public ApiResponse<CountVO> unreadCount() {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(new CountVO(notificationService.unreadCount(user)));
    }

    /**
     * 标记单条为已读（已读状态按人存，不影响其它人）。
     */
    @PostMapping("/{notifyId}/read")
    public ApiResponse<Void> markRead(
            @PathVariable String notifyId
    ) {
        LoginUser user = LoginUserContext.require();
        notificationService.markRead(user, notifyId);
        return ApiResponse.ok();
    }

    /**
     * 一键全部已读，可按通知类型只清某一类。
     */
    @PostMapping("/read-all")
    public ApiResponse<CountVO> markAllRead(
            @RequestParam(required = false) Integer notifyType
    ) {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(new CountVO(notificationService.markAllRead(user, notifyType)));
    }
}
