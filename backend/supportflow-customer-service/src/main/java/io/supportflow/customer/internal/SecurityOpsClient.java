package io.supportflow.customer.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;
import io.supportflow.customer.dto.customer.ExportDecisionVO;
import io.supportflow.customer.dto.customer.ExportPendingVO;
import io.supportflow.customer.dto.customer.ExportRequestVO;
import io.supportflow.customer.dto.session.ConsolePolicyVO;

/**
 * ops-service 安全中心内部客户端：导出前校验、提交导出申请、校验开放 API 密钥。
 *
 * <p>对接的是 ops 的 {@code /api/ops/internal/security/**}。三个调用都是"执行点"，
 * 所以失败时的策略必须明确：</p>
 * <ul>
 *   <li><b>导出校验</b>：拿不到就<b>拒绝</b>（宁可让运营重试，也不能在策略不明时把客户数据导出去）；</li>
 *   <li><b>提交申请</b>：失败要如实告诉企业"申请没提交成功"，不能假装成功；</li>
 *   <li><b>密钥校验</b>：拿不到就<b>拒绝</b>（开放接口宁可 503，也不能放未鉴权的请求进来）。</li>
 * </ul>
 */
@Component
public class SecurityOpsClient {

    private static final Logger log = LoggerFactory.getLogger(SecurityOpsClient.class);

    private final RestClient restClient;

    public SecurityOpsClient(@Value("${supportflow.ops.internal-base-url:http://127.0.0.1:9095}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    /** 导出前校验 + 留痕 */
    public ExportDecisionVO consumeExport(String tenantCode, int exportType, int rows) {
        try {
            Map<String, Object> body = restClient.post()
                    .uri("/api/ops/internal/security/exports/consume")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("tenantCode", tenantCode, "exportType", exportType, "rows", rows))
                    .retrieve().body(Map.class);
            Object data = body == null ? null : body.get("data");
            if (data instanceof Map<?, ?> map) {
                return decision(map);
            }
        } catch (Exception e) {
            log.warn("导出校验失败（按拒绝处理）tenant={} error={}", tenantCode, e.getMessage());
        }
        return new ExportDecisionVO(false, "暂时无法校验导出权限，请稍后重试或联系平台运营", false, null);
    }

    /** ops 侧的统一判定结构 → 实体（两个判定接口共用） */
    private static ExportDecisionVO decision(Map<?, ?> data) {
        return new ExportDecisionVO(Boolean.TRUE.equals(data.get("allowed")),
                str(data.get("reason")), Boolean.TRUE.equals(data.get("audited")),
                str(data.get("exportId")));
    }

    /**
     * 本租户可用的导出申请单（消息中心点「下载」时用）：{found, exportId, scopeJson}。
     *
     * <p>拿不到就按「没有可用审批」处理——宁可让企业再提交一次申请，
     * 也不能在没有审批依据的情况下把客户数据导出去。</p>
     */
    public ExportPendingVO pendingExport(String tenantCode, int exportType) {
        try {
            Map<String, Object> body = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/api/ops/internal/security/exports/pending")
                            .queryParam("tenantCode", tenantCode)
                            .queryParam("exportType", exportType)
                            .build())
                    .retrieve().body(Map.class);
            Object data = body == null ? null : body.get("data");
            if (data instanceof Map<?, ?> map) {
                return new ExportPendingVO(Boolean.TRUE.equals(map.get("found")),
                        Boolean.TRUE.equals(map.get("used")), str(map.get("exportId")),
                        str(map.get("scopeJson")), str(map.get("approveTime")), null, null);
            }
        } catch (Exception e) {
            log.warn("取可用导出申请失败（按没有审批处理）tenant={} error={}", tenantCode, e.getMessage());
            // 区分「确实没有审批单」和「ops 拿不到」：前者让企业去申请，
            // 后者八成是 ops-service 没重启（新接口不存在会 404），提示语完全不同
            return new ExportPendingVO(false, false, null, null, null, true, brief(e.getMessage()));
        }
        return new ExportPendingVO(false, false, null, null, null, null, null);
    }

    /**
     * 按申请单号精确消费一次（下载文件前回写实际导出行数）。
     *
     * <p>拿不到同样按拒绝处理：这里放过去，就等于「审批服务一挂，谁都能导」。</p>
     */
    public ExportDecisionVO consumeExportById(String tenantCode, long exportId, int rows) {
        try {
            Map<String, Object> body = restClient.post()
                    .uri("/api/ops/internal/security/exports/consume-by-id")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("tenantCode", tenantCode, "exportId", exportId, "rows", rows))
                    .retrieve().body(Map.class);
            Object data = body == null ? null : body.get("data");
            if (data instanceof Map<?, ?> map) {
                return decision(map);
            }
        } catch (Exception e) {
            log.warn("按申请单消费失败（按拒绝处理）tenant={} exportId={} error={}",
                    tenantCode, exportId, e.getMessage());
        }
        return new ExportDecisionVO(false, "暂时无法校验导出权限，请稍后重试或联系平台运营", false, null);
    }

    /** 提交导出申请 */
    @SuppressWarnings("unchecked")
    public ExportRequestVO requestExport(String tenantCode, int exportType, String scopeJson,
                                         Long requesterId, String requesterName) {
        try {
            Map<String, Object> body = restClient.post()
                    .uri("/api/ops/internal/security/exports/request")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("tenantCode", tenantCode,
                            "exportType", exportType,
                            "scopeJson", scopeJson == null ? "" : scopeJson,
                            "requesterId", requesterId == null ? 0L : requesterId,
                            "requesterName", requesterName == null ? "" : requesterName))
                    .retrieve().body(Map.class);
            Object data = body == null ? null : body.get("data");
            if (data instanceof Map<?, ?> map) {
                return new ExportRequestVO(str(map.get("id")),
                        map.get("status") instanceof Number n ? n.intValue() : null,
                        map.get("duplicated") instanceof Boolean b ? b : null, null, null);
            }
        } catch (Exception e) {
            log.warn("提交导出申请失败 tenant={} error={}", tenantCode, e.getMessage());
            return new ExportRequestVO("", 0, null, true, brief(e.getMessage()));
        }
        // 失败要能被上层认出来：早期版本这里返回空 Map，控制器把它当成功回给企业，
        // 现象是"页面提示申请已提交，平台却看不到记录"——这种假成功比报错更糟。
        return new ExportRequestVO("", 0, null, true, null);
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 错误信息截断：只带最有辨识度的一段（404 / 500 / SQL 报错都在里面） */
    private static String brief(String raw) {
        if (raw == null || raw.isBlank()) {
            return "无响应";
        }
        String text = raw.replaceAll("\\s+", " ").trim();
        return text.length() > 160 ? text.substring(0, 160) : text;
    }

    /**
     * 管理端显示策略（水印开关 + 空闲登出时长）：**登录即可读**，企业账号也要用。
     *
     * <p>拿不到就返回默认值（水印开、120 分钟）——策略读不到不该影响后台能不能用。</p>
     */
    @SuppressWarnings("unchecked")
    public ConsolePolicyVO consolePolicy() {
        try {
            Map<String, Object> body = restClient.get()
                    .uri("/api/ops/internal/security/console-policy")
                    .retrieve().body(Map.class);
            Object data = body == null ? null : body.get("data");
            if (data instanceof Map<?, ?> map) {
                return new ConsolePolicyVO(map.get("watermarkEnabled") instanceof Boolean b ? b : null,
                        map.get("sessionTimeoutMinutes") instanceof Number n ? n.intValue() : null);
            }
        } catch (Exception e) {
            log.debug("取管理端显示策略失败，用默认值：{}", e.getMessage());
        }
        return new ConsolePolicyVO(true, 120);
    }

    /** 校验开放 API 密钥：命中返回租户编码，否则 null */
    @SuppressWarnings("unchecked")
    public String verifyApiKey(String apiKey) {
        try {
            Map<String, Object> body = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/api/ops/internal/security/api-keys/verify")
                            .queryParam("apiKey", apiKey).build())
                    .retrieve().body(Map.class);
            Object data = body == null ? null : body.get("data");
            if (data instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("valid"))) {
                Object tenant = map.get("tenantCode");
                return tenant == null ? null : String.valueOf(tenant);
            }
        } catch (Exception e) {
            log.warn("密钥校验失败（按拒绝处理）：{}", e.getMessage());
        }
        return null;
    }
}
