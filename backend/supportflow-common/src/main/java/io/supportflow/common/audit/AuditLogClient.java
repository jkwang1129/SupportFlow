package io.supportflow.common.audit;

import io.supportflow.common.auth.LoginUser;
import io.supportflow.common.web.RequestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 审计日志写入端（各服务共用，放在 common 里避免每个服务抄一遍）。
 *
 * <p>三条约定：</p>
 * <ol>
 *   <li><b>旁路，绝不影响业务</b>：写不进去（ops-service 没起、网络抖）只记一行 warn，
 *       不抛异常、不回滚业务事务。审计的价值是"有据可查"，不是"卡住主流程"；</li>
 *   <li><b>只追加</b>：审计日志不更新、不删除（表注释也是这么写的），
 *       所以这里只有 record，没有 update/delete；</li>
 *   <li><b>姓名当场快照</b>：账号改名之后，历史日志仍要保留当时的操作人，
 *       所以把 {@code name} 一起写进去，而不是只存 ID 让页面去反查。</li>
 * </ol>
 */
@Component
public class AuditLogClient {

    private static final Logger log = LoggerFactory.getLogger(AuditLogClient.class);

    /** 平台级操作（审核、租户治理、计费）的租户编码 */
    public static final String PLATFORM = "PLATFORM";

    private final RestClient restClient;

    public AuditLogClient(@Value("${supportflow.ops.internal-base-url:http://127.0.0.1:9095}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    /** 不带对象的操作（例如"批量出账"） */
    public void record(LoginUser operator, String module, String action, boolean success, String detail) {
        record(operator, module, action, null, null, success, detail);
    }

    /**
     * 记一条审计日志。
     *
     * @param operator   操作人；定时任务传 null（会记成"系统"）
     * @param module     模块，与前端筛选器的选项保持一致
     * @param action     操作
     * @param targetType 对象类型，可为 null
     * @param targetId   对象标识，可为 null
     * @param success    成功 / 失败——失败也要记，审计的价值一半在失败上
     * @param detail     详情
     */
    public void record(LoginUser operator, String module, String action,
                       String targetType, String targetId, boolean success, String detail) {
        try {
            AuditLogRecord record = new AuditLogRecord(
                    tenantOf(operator),
                    operator == null ? null : operator.userId(),
                    operator == null ? "系统" : operator.name(),
                    module,
                    action,
                    targetType,
                    targetId,
                    success ? 1 : 2,
                    detail,
                    currentIp(),
                    null);
            restClient.post()
                    .uri("/api/ops/internal/audit-logs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(record)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("写审计日志失败 module={} action={} error={}", module, action, e.getMessage());
        }
    }

    private static String tenantOf(LoginUser operator) {
        if (operator == null || operator.tenantCode() == null || operator.tenantCode().isBlank()) {
            return PLATFORM;
        }
        return operator.tenantCode();
    }

    /**
     * 从当前请求里取来源 IP。
     *
     * <p>网关（WebFlux）里没有 Servlet 请求上下文，这里会直接返回 null；
     * 定时任务同理。所以整段都包在 try 里——日志字段缺失可以接受，抛异常不行。
     * 取值口径统一走 {@link RequestUtils#clientIp}，别在这里再抄一份。</p>
     */
    private static String currentIp() {
        try {
            if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
                return null;
            }
            return RequestUtils.clientIp(attributes.getRequest());
        } catch (Exception e) {
            return null;
        }
    }
}
