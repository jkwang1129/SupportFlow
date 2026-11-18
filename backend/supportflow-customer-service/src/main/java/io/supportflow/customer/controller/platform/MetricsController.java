package io.supportflow.customer.controller.platform;

import io.supportflow.common.context.LoginUserContext;
import io.supportflow.common.api.ApiResponse;
import io.supportflow.common.auth.LoginUser;
import io.supportflow.customer.dto.metrics.AccessVO;
import io.supportflow.customer.dto.metrics.AgentDrillVO;
import io.supportflow.customer.dto.metrics.AgentReportVO;
import io.supportflow.customer.dto.metrics.MetricsRebuildVO;
import io.supportflow.customer.dto.metrics.RealtimeVO;
import io.supportflow.customer.dto.metrics.ReportQuery;
import io.supportflow.customer.service.metrics.MetricsService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 数据大屏与坐席绩效：实时指标、绩效报表与下钻、重算、导出。
 *
 * <p>挂 {@code /api/customer/metrics}：和客户 360 一样是企业侧接口，网关与前端代理都不用再动。
 * 权限（只有管理员 / 主管能看）在 service 里统一判，控制器只负责取登录态与参数。</p>
 */
@RestController
@RequestMapping("/api/customer/metrics")
public class MetricsController {

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    private final MetricsService metricsService;

    public MetricsController(MetricsService metricsService) {
        this.metricsService = metricsService;
    }

    /** 大屏实时指标（页面每 5 秒刷一次） */
    @GetMapping("/realtime")
    public ApiResponse<RealtimeVO> realtime() {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(metricsService.realtime(user));
    }

    /** 当前登录人能不能看数据报表（前端据此显隐菜单） */
    @GetMapping("/access")
    public ApiResponse<AccessVO> access() {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(metricsService.access(user));
    }

    /** 坐席绩效报表（时间范围 + 排序 + 分页） */
    @GetMapping("/agents")
    public ApiResponse<AgentReportVO> agents(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long agentId,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize
    ) {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(metricsService.agentReport(user,
                new ReportQuery(from, to, agentId, sort), page, pageSize));
    }

    /** 单个坐席下钻：按天趋势 + 会话明细 */
    @GetMapping("/agents/{agentId}")
    public ApiResponse<AgentDrillVO> agentDetail(
            @PathVariable String agentId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(metricsService.agentDetail(user, agentId,
                new ReportQuery(from, to, null, null)));
    }

    /** 重算某一天的坐席绩效（幂等）；不传 date 就是今天 */
    @PostMapping("/rebuild")
    public ApiResponse<MetricsRebuildVO> rebuild(
            @RequestParam(required = false) String date
    ) {
        LoginUser user = LoginUserContext.require();
        int rows = metricsService.rebuild(user, date);
        return ApiResponse.ok(new MetricsRebuildVO(
                date == null ? LocalDate.now().toString() : date, rows));
    }

    /** 导出绩效 CSV（按当前筛选，带 BOM） */
    @GetMapping("/agents/export")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long agentId,
            @RequestParam(required = false) String sort
    ) {
        LoginUser user = LoginUserContext.require();
        String csv = metricsService.exportCsv(user, new ReportQuery(from, to, agentId, sort));
        String fileName = "坐席绩效-" + LocalDateTime.now().format(FILE_TIME) + ".csv";
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"agent-metrics.csv\"; filename*=UTF-8''" + encoded)
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }
}
