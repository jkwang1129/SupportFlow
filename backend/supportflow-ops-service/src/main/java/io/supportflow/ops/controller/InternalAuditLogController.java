package io.supportflow.ops.controller;

import io.supportflow.common.api.AcceptedCountVO;
import io.supportflow.common.api.ApiResponse;
import io.supportflow.common.audit.AuditLogRecord;
import io.supportflow.ops.service.AuditLogService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 审计日志写入（内部接口）：各服务的关键写操作通过 {@code AuditLogClient} 报进来。
 *
 * <p>为什么不校验登录态：这是服务间调用，操作人信息已经在报文里；
 * 靠网关的免限流白名单与内网隔离保护即可。让各服务为了写日志再走一次登录，
 * 反而会把审计变成链路里最容易断的一环。</p>
 */
@RestController
@RequestMapping("/api/ops/internal/audit-logs")
public class InternalAuditLogController {

    private final AuditLogService auditLogService;

    public InternalAuditLogController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    /** 记一条审计日志 */
    @PostMapping
    public ApiResponse<AcceptedCountVO> record(@RequestBody AuditLogRecord body) {
        auditLogService.record(body);
        return ApiResponse.ok(new AcceptedCountVO(1));
    }
}
