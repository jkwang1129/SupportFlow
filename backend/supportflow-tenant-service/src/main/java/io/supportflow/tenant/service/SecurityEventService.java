package io.supportflow.tenant.service;

import io.supportflow.common.audit.AuditLogClient;
import io.supportflow.common.auth.LoginUser;
import io.supportflow.common.exception.BizException;
import io.supportflow.tenant.dto.SecurityEventDailyVO;
import io.supportflow.tenant.dto.SecurityEventHandleVO;
import io.supportflow.tenant.dto.SecurityEventPageVO;
import io.supportflow.tenant.dto.SecurityEventSummaryVO;
import io.supportflow.tenant.dto.SecurityEventTypeCountVO;
import io.supportflow.tenant.dto.SecurityEventVO;
import io.supportflow.tenant.mapper.SecurityEventMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 安全事件中心（平台「安全中心 → 安全事件」）。
 *
 * <p>事件由探测侧写入（{@code tenant_security_event}：租户头与令牌不一致、跨租户资源访问、
 * 被限流拒绝、RLS 拦截）。这里只做两件事：<b>跨租户聚合给人看</b>，以及<b>标记处置</b>。
 * 事件内容本身不可改——"探测到的事实"不该被运营改掉。</p>
 *
 * <p>与「审计日志」的分工：审计答"谁改了什么"，这里答"谁在试着越界"。</p>
 */
@Service
public class SecurityEventService {

    /** 审计模块名（与平台页筛选器一致） */
    private static final String MODULE = "安全中心";

    /** 一次最多 200 行：安全事件是"逐个看、逐个处置"的活儿 */
    private static final int MAX_PAGE_SIZE = 200;

    private final SecurityEventMapper mapper;
    private final AuditLogClient auditLogClient;

    public SecurityEventService(SecurityEventMapper mapper, AuditLogClient auditLogClient) {
        this.mapper = mapper;
        this.auditLogClient = auditLogClient;
    }

    /** 事件列表 + 概览 + 近 7 天趋势 */
    public SecurityEventPageVO events(Integer eventType, Integer handleStatus, String tenantCode,
                                      int page, int pageSize) {
        int current = Math.max(1, page);
        int size = Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE);
        Integer type = eventType == null || eventType <= 0 ? null : eventType;
        Integer handle = handleStatus == null || handleStatus <= 0 ? null : handleStatus;
        String tenant = tenantCode == null || tenantCode.isBlank() ? null : tenantCode.trim();

        long total = mapper.countEvents(type, handle, tenant);
        List<Map<String, Object>> rows = mapper.selectEvents(type, handle, tenant,
                size, (long) (current - 1) * size);
        List<SecurityEventVO> list = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            int typeCode = intValue(row.get("event_type"));
            int status = intValue(row.get("handle_status"));
            list.add(new SecurityEventVO(String.valueOf(row.get("id")), str(row.get("tenant_code")),
                    row.get("company_name") == null ? str(row.get("tenant_code"))
                            : str(row.get("company_name")),
                    typeCode, eventTypeText(typeCode), str(row.get("detail")),
                    str(row.get("request_uri")), str(row.get("request_method")),
                    str(row.get("client_ip")), str(row.get("token_tenant")),
                    str(row.get("operator_name")), text(row.get("event_time")), status,
                    handleStatusText(status), str(row.get("handle_remark")),
                    str(row.get("handler_name")), text(row.get("handle_time"))));
        }

        Map<String, Object> raw = mapper.selectOverview();
        SecurityEventSummaryVO summary = new SecurityEventSummaryVO(
                intValue(raw == null ? null : raw.get("total")),
                intValue(raw == null ? null : raw.get("pending")),
                intValue(raw == null ? null : raw.get("today")),
                intValue(raw == null ? null : raw.get("last7")));
        List<SecurityEventTypeCountVO> typeDistribution = new ArrayList<>();
        for (int code = 1; code <= 4; code++) {
            typeDistribution.add(new SecurityEventTypeCountVO(code, eventTypeText(code),
                    intValue(raw == null ? null : raw.get("type" + code))));
        }

        List<SecurityEventDailyVO> daily = new ArrayList<>();
        for (Map<String, Object> row : mapper.selectDaily(7)) {
            daily.add(new SecurityEventDailyVO(String.valueOf(row.get("stat_date")),
                    intValue(row.get("events"))));
        }

        return new SecurityEventPageVO((int) total, current, size, list, summary, typeDistribution,
                daily);
    }

    /** 处置事件：2-已确认（真实越界）、3-已忽略（误报） */
    @Transactional
    public SecurityEventHandleVO handle(LoginUser operator, long id, int status, String remark) {
        if (status != 2 && status != 3) {
            throw new BizException(40001, "只支持标记已确认（2）或已忽略（3）");
        }
        int updated = mapper.handle(id, status, remark, operator == null ? "系统" : operator.name());
        if (updated == 0) {
            throw new BizException(40401, "安全事件不存在：" + id);
        }
        auditLogClient.record(operator, MODULE, status == 2 ? "确认安全事件" : "忽略安全事件",
                "安全事件", String.valueOf(id), true,
                (status == 2 ? "确认" : "忽略") + "安全事件 #" + id
                        + (remark == null || remark.isBlank() ? "" : "，说明：" + remark));
        return new SecurityEventHandleVO(String.valueOf(id), status);
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String eventTypeText(int type) {
        return switch (type) {
            case 1 -> "租户头与令牌不一致";
            case 2 -> "跨租户资源访问";
            case 3 -> "被限流拒绝";
            case 4 -> "RLS 拦截";
            default -> "其它事件";
        };
    }

    private static String handleStatusText(int status) {
        return switch (status) {
            case 2 -> "已确认";
            case 3 -> "已忽略";
            default -> "待处理";
        };
    }

    private static int intValue(Object raw) {
        if (raw == null) {
            return 0;
        }
        if (raw instanceof BigDecimal decimal) {
            return decimal.intValue();
        }
        if (raw instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String text(Object raw) {
        if (raw == null) {
            return null;
        }
        String value = String.valueOf(raw);
        return value.length() >= 19 ? value.replace('T', ' ').substring(0, 19) : value;
    }
}
