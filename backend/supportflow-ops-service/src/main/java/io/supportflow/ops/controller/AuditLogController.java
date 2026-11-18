package io.supportflow.ops.controller;

import io.supportflow.common.context.LoginUserContext;
import io.supportflow.common.api.ApiResponse;
import io.supportflow.common.auth.LoginUser;
import io.supportflow.common.exception.BizException;
import io.supportflow.ops.dto.AuditLogPageVO;
import io.supportflow.ops.dto.AuditModuleVO;
import io.supportflow.ops.service.AuditLogService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 审计日志（平台侧）：全平台操作留痕，按模块 / 结果 / 操作人 / 时间查，可导出。
 *
 * <p>只读 + 导出：日志的写入在内部接口（{@link InternalAuditLogController}），
 * 平台页连改都改不了——审计日志能改就不叫审计日志了。</p>
 */
@RestController
@RequestMapping("/api/ops/audit-logs")
public class AuditLogController {

    private final AuditLogService auditLogService;

    public AuditLogController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    /** 日志列表（分页 + 筛选） */
    @GetMapping
    public ApiResponse<AuditLogPageVO> list(
            @RequestParam(required = false) String module,
            @RequestParam(required = false) Integer result,
            @RequestParam(required = false) String operator,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize
    ) {
        requirePlatform();
        return ApiResponse.ok(auditLogService.page(module, result, operator, keyword, from, to,
                Math.max(1, page), Math.min(Math.max(1, pageSize), 100)));
    }

    /** 模块清单（有日志的模块，供筛选下拉用） */
    @GetMapping("/modules")
    public ApiResponse<List<AuditModuleVO>> modules() {
        requirePlatform();
        return ApiResponse.ok(auditLogService.modules());
    }

    /** 导出 CSV（带 BOM，Excel 直接打开） */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) String module,
            @RequestParam(required = false) Integer result,
            @RequestParam(required = false) String operator,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to
    ) {
        requirePlatform();
        byte[] body = auditLogService.exportCsv(module, result, operator, keyword, from, to)
                .getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"audit-logs.csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }

    private LoginUser requirePlatform() {
        LoginUser user = LoginUserContext.require();
        if (user.userType() != 1) {
            throw new BizException(40302, "只有平台账号能查看审计日志");
        }
        return user;
    }
}
