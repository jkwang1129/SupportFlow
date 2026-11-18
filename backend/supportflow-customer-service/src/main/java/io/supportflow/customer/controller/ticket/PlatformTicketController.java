package io.supportflow.customer.controller.ticket;

import io.supportflow.common.context.LoginUserContext;
import io.supportflow.common.api.ApiResponse;
import io.supportflow.common.auth.LoginUser;
import io.supportflow.customer.dto.ticket.PlatformTicketOverviewVO;
import io.supportflow.customer.dto.ticket.ReplyBody;
import io.supportflow.customer.dto.ticket.StatusBody;
import io.supportflow.customer.dto.ticket.TicketDetailVO;
import io.supportflow.customer.dto.ticket.TicketPageVO;
import io.supportflow.customer.dto.ticket.TicketQuery;
import io.supportflow.customer.service.ticket.PlatformTicketService;
import io.supportflow.customer.service.ticket.TicketService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台侧：支持工单（企业提给平台的问题）列表与处理。
 *
 * <p>和 `/api/customer/tickets` 分开挂：那边是企业租户内的工单，鉴权口径是"本租户"；
 * 这边是跨租户的平台视角，鉴权口径是"平台账号"，两者混在一个 Controller 里最容易出越权。</p>
 */
@RestController
@RequestMapping("/api/platform/tickets")
public class PlatformTicketController {

    private final PlatformTicketService platformTicketService;

    public PlatformTicketController(PlatformTicketService platformTicketService) {
        this.platformTicketService = platformTicketService;
    }

    /** 平台侧工单看板：跨租户的待处理 / 处理中 / 超时统计。 */
    @GetMapping("/overview")
    public ApiResponse<PlatformTicketOverviewVO> overview() {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(platformTicketService.overview(user));
    }

    /**
     * 跨租户列表：`tenant` 传租户号前几位即可模糊筛（前端筛选栏的"租户号"）。
     */
    @GetMapping
    public ApiResponse<TicketPageVO> list(
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Integer priority,
            @RequestParam(required = false) Integer slaState,
            @RequestParam(required = false) String tenant,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean mineOnly,
            @RequestParam(required = false) Boolean unassignedOnly,
            @RequestParam(required = false, defaultValue = "1") Integer page,
            @RequestParam(required = false, defaultValue = "10") Integer pageSize
    ) {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(platformTicketService.list(user, new TicketQuery(
                status, priority, null, slaState, null, mineOnly, unassignedOnly, tenant, keyword, null),
                page, pageSize));
    }

    /** 工单详情（平台视角可跨租户查看）。 */
    @GetMapping("/{ticketNo}")
    public ApiResponse<TicketDetailVO> detail(
            @PathVariable String ticketNo,
            @RequestParam String tenant
    ) {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(platformTicketService.detail(user, tenant, ticketNo));
    }

    /** 平台认领工单（认领后处理人变成当前账号）。 */
    @PostMapping("/{ticketNo}/claim")
    public ApiResponse<TicketDetailVO> claim(
            @PathVariable String ticketNo,
            @RequestParam String tenant
    ) {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(platformTicketService.claim(user, tenant, ticketNo));
    }

    /** 平台回复工单（回复内容会同步到企业侧与关联会话）。 */
    @PostMapping("/{ticketNo}/reply")
    public ApiResponse<TicketDetailVO> reply(
            @PathVariable String ticketNo,
            @RequestParam String tenant,
            @RequestBody ReplyBody body
    ) {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(platformTicketService.reply(user, tenant, ticketNo, body));
    }

    /** 平台改工单状态（含"待企业确认 → 已解决"这类跨侧流转）。 */
    @PostMapping("/{ticketNo}/status")
    public ApiResponse<TicketDetailVO> changeStatus(
            @PathVariable String ticketNo,
            @RequestParam String tenant,
            @RequestBody StatusBody body
    ) {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(platformTicketService.changeStatus(user, tenant, ticketNo, body));
    }

    /** 平台侧升级：一线处理不动就提优先级（并通知企业），同样不重算 SLA 截止时间。 */
    @PostMapping("/{ticketNo}/escalate")
    public ApiResponse<TicketDetailVO> escalate(
            @PathVariable String ticketNo,
            @RequestParam String tenant,
            @RequestBody(required = false) StatusBody body
    ) {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(platformTicketService.escalate(user, tenant, ticketNo, body));
    }

    /** 平台侧导出 CSV（跨租户，带租户号） */
    @GetMapping(value = "/export", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Integer priority,
            @RequestParam(required = false) Integer slaState,
            @RequestParam(required = false) String tenant,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean mineOnly,
            @RequestParam(required = false) Boolean unassignedOnly
    ) {
        LoginUser user = LoginUserContext.require();
        String csv = platformTicketService.exportCsv(user, new TicketQuery(
                status, priority, null, slaState, null, mineOnly, unassignedOnly, tenant, keyword, null));
        return TicketController.csvResponse(csv, "platform-support-tickets.csv");
    }
}
