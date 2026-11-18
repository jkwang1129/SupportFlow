package io.supportflow.ops.service;

import io.supportflow.common.audit.AuditLogClient;
import io.supportflow.common.auth.LoginUser;
import io.supportflow.common.exception.BizException;
import io.supportflow.common.id.SnowflakeIdGenerator;
import io.supportflow.ops.dto.ExportDecisionVO;
import io.supportflow.ops.dto.ExportPageVO;
import io.supportflow.ops.dto.ExportPendingVO;
import io.supportflow.ops.dto.ExportRequestVO;
import io.supportflow.ops.dto.ExportRowVO;
import io.supportflow.ops.dto.ExportSummaryVO;
import io.supportflow.ops.entity.DataExport;
import io.supportflow.ops.internal.CustomerNotificationClient;
import io.supportflow.ops.mapper.DataExportMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 数据导出审批（平台「安全中心 → 导出审批」）。

 * <p>为什么要有这块：客户 360 能导手机号、工单能导客户诉求——这些导出以前点一下就直接下载，
 * 一点痕迹都没有。现在的闭环是：<b>企业提交申请 → 平台审批 → 通过后才能导（一次审批只能用一次）</b>，
 * 而且"申请"和"实际导出"写在同一行，出事能回答"批了多少、实际导了多少"。</p>
 *
 * <p>策略开关（{@code security_policy.export_need_approval}）决定要不要审批：关掉就直接放行，
 * 但**仍然会被调用方写审计**——审批可以关，留痕不能关。</p>
 */
@Service
public class ExportApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ExportApprovalService.class);

    /** 审计模块名（与平台页筛选器一致） */
    private static final String MODULE = "安全中心";

    /** 1-待审批、2-已通过、3-已驳回 */
    private static final int PENDING = 1;
    private static final int APPROVED = 2;
    private static final int REJECTED = 3;

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final DataExportMapper dataExportMapper;
    private final SecurityPolicyService policyService;
    private final SnowflakeIdGenerator idGenerator;
    private final AuditLogClient auditLogClient;
    private final CustomerNotificationClient notificationClient;

    public ExportApprovalService(DataExportMapper dataExportMapper,
                                 SecurityPolicyService policyService,
                                 SnowflakeIdGenerator idGenerator,
                                 AuditLogClient auditLogClient,
                                 CustomerNotificationClient notificationClient) {
        this.dataExportMapper = dataExportMapper;
        this.policyService = policyService;
        this.idGenerator = idGenerator;
        this.auditLogClient = auditLogClient;
        this.notificationClient = notificationClient;
    }

    // ------------------------------------------------------------------ 查询

    public ExportPageVO exports(Integer status, String tenantCode, int page, int pageSize) {
        String tenant = tenantCode == null || tenantCode.isBlank() ? null : tenantCode.trim();
        boolean filterStatus = status != null && status > 0;
        long total = dataExportMapper.selectCount(Wrappers.<DataExport>lambdaQuery()
                .eq(DataExport::getDeleted, false)
                .eq(filterStatus, DataExport::getStatus, status)
                .eq(tenant != null, DataExport::getTenantCode, tenant));
        int size = Math.min(Math.max(1, pageSize), 100);
        int current = Math.max(1, page);
        List<DataExport> rows = dataExportMapper.selectList(Wrappers.<DataExport>lambdaQuery()
                .eq(DataExport::getDeleted, false)
                .eq(filterStatus, DataExport::getStatus, status)
                .eq(tenant != null, DataExport::getTenantCode, tenant)
                .orderByAsc(DataExport::getStatus)
                .orderByDesc(DataExport::getRequestTime)
                .last("LIMIT " + size + " OFFSET " + (long) (current - 1) * size));
        List<ExportRowVO> list = new ArrayList<>(rows.size());
        for (DataExport row : rows) {
            list.add(toVO(row));
        }
        return new ExportPageVO((int) total, current, size, list, summary());
    }

    public ExportSummaryVO summary() {
        return new ExportSummaryVO((int) countByStatus(PENDING), (int) countByStatus(APPROVED),
                (int) countByStatus(REJECTED));
    }

    // ------------------------------------------------------------------ 审批

    /** 通过：写审批人 / 时间 + 审计 + 通知申请企业 */
    @Transactional
    public ExportRowVO approve(LoginUser operator, long id, String remark) {
        DataExport existing = requireExport(id);
        if (existing.getStatus() != null && existing.getStatus() != PENDING) {
            throw new BizException(40001, "这条申请已经处理过了");
        }
        LocalDateTime now = LocalDateTime.now();
        DataExport update = new DataExport();
        update.setId(id);
        update.setStatus((short) APPROVED);
        update.setApproverId(operator == null ? null : operator.userId());
        update.setApproveTime(now);
        update.setUpdateTime(now);
        update.setEditor(operator == null ? "系统" : operator.name());
        dataExportMapper.updateById(update);
        notificationClient.publish(existing.getTenantCode(), 3, "数据导出申请已通过",
                "你提交的" + exportTypeText(existing.getExportType()) + "导出申请已通过，"
                        + "点这条消息就能直接下载（本次导出的行数会被记录）。",
                "customer-export");
        auditLogClient.record(operator, MODULE, "通过导出申请", "租户", existing.getTenantCode(), true,
                "通过租户 " + existing.getTenantCode() + " 的" + exportTypeText(existing.getExportType())
                        + "导出申请" + (remark == null || remark.isBlank() ? "" : "，备注：" + remark));
        return toVO(requireExport(id));
    }

    /** 驳回：写原因 + 审计 + 通知申请企业 */
    @Transactional
    public ExportRowVO reject(LoginUser operator, long id, String reason) {
        DataExport existing = requireExport(id);
        if (existing.getStatus() != null && existing.getStatus() != PENDING) {
            throw new BizException(40001, "这条申请已经处理过了");
        }
        LocalDateTime now = LocalDateTime.now();
        DataExport update = new DataExport();
        update.setId(id);
        update.setStatus((short) REJECTED);
        update.setApproverId(operator == null ? null : operator.userId());
        update.setApproveTime(now);
        update.setRejectReason(reason);
        update.setUpdateTime(now);
        update.setEditor(operator == null ? "系统" : operator.name());
        dataExportMapper.updateById(update);
        notificationClient.publish(existing.getTenantCode(), 3, "数据导出申请被驳回",
                "你提交的" + exportTypeText(existing.getExportType()) + "导出申请被驳回"
                        + (reason == null || reason.isBlank() ? "。" : "，原因：" + reason),
                "customers");
        auditLogClient.record(operator, MODULE, "驳回导出申请", "租户", existing.getTenantCode(), true,
                "驳回租户 " + existing.getTenantCode() + " 的导出申请"
                        + (reason == null || reason.isBlank() ? "" : "，原因：" + reason));
        return toVO(requireExport(id));
    }

    // ------------------------------------------------------------------ 内部（导出接口调用）

    /**
     * 导出前校验 + 留痕。
     *
     * <p>策略开了审批就要求该租户该类型有一条"已通过且还没用过"的申请；用掉时回写导出时间与行数
     * （所以一次审批只能导一次）。策略关了直接放行——调用方仍然会写审计，审批可以关、留痕不能关。</p>
     */
    @Transactional
    public ExportDecisionVO consume(String tenantCode, int exportType, int rows) {
        if (!policyService.exportNeedApproval()) {
            return new ExportDecisionVO(true, "当前策略：导出不需要审批", false, null);
        }
        DataExport approved = dataExportMapper.selectOne(Wrappers.<DataExport>lambdaQuery()
                .eq(DataExport::getTenantCode, tenantCode)
                .eq(DataExport::getExportType, (short) exportType)
                .eq(DataExport::getStatus, APPROVED)
                .isNull(DataExport::getExportTime)
                .eq(DataExport::getDeleted, false)
                .orderByAsc(DataExport::getApproveTime)
                .last("LIMIT 1"));
        if (approved == null) {
            return new ExportDecisionVO(false,
                    "这类数据导出需要平台审批：请先提交导出申请，等平台通过后再导", false, null);
        }
        LocalDateTime now = LocalDateTime.now();
        DataExport update = new DataExport();
        update.setId(approved.getId());
        update.setExportRows(rows);
        update.setExportTime(now);
        update.setUpdateTime(now);
        dataExportMapper.updateById(update);
        return new ExportDecisionVO(true, "已通过审批（申请单 " + approved.getId() + "）", true,
                String.valueOf(approved.getId()));
    }

    /**
     * 本租户「已通过且还没导出过」的那张申请单（消息中心点下载时用）。
     *
     * <p>为什么要单独一个内部接口：企业点的是消息里的下载，**它并不知道自己的申请单号**，
     * 而导出范围（筛选条件）存在申请单上。让前端把范围传上来等于把审批范围交给前端决定——
     * 审批的是「退款类客户」，导出变成「全部客户」这种事不该有可能发生，
     * 所以范围一律由服务端从审批单里读。</p>
     */
    public ExportPendingVO pending(String tenantCode, int exportType) {
        if (tenantCode == null || tenantCode.isBlank()) {
            return new ExportPendingVO(false, false, null, null, null);
        }
        DataExport approved = dataExportMapper.selectOne(Wrappers.<DataExport>lambdaQuery()
                .eq(DataExport::getTenantCode, tenantCode)
                .eq(DataExport::getExportType, (short) exportType)
                .eq(DataExport::getStatus, APPROVED)
                .isNull(DataExport::getExportTime)
                .eq(DataExport::getDeleted, false)
                .orderByAsc(DataExport::getApproveTime)
                .last("LIMIT 1"));
        if (approved == null) {
            // 没有"可用"的单子，但要分清"还没申请"和"已经导过了"——
            // 两者给企业的下一步动作完全不同（去申请 vs 不用管/重新申请）
            boolean used = dataExportMapper.selectCount(Wrappers.<DataExport>lambdaQuery()
                    .eq(DataExport::getTenantCode, tenantCode)
                    .eq(DataExport::getExportType, (short) exportType)
                    .eq(DataExport::getStatus, APPROVED)
                    .isNotNull(DataExport::getExportTime)
                    .eq(DataExport::getDeleted, false)) > 0;
            return new ExportPendingVO(false, used, null, null, null);
        }
        return new ExportPendingVO(true, false, String.valueOf(approved.getId()),
                approved.getScopeJson(), text(approved.getApproveTime()));
    }

    /**
     * 按申请单号精确消费一次（下载文件前回写实际导出行数）。
     *
     * <p>与 {@link #consume} 的差别：那个是「有可用审批单就放行」（企业自己在页面上点导出），
     * 这个是「就用这一张」（消息里点下载）——多一个「必须属于当前企业」的判断，
     * 免得有人拿别家企业的申请单号来试。</p>
     *
     * <p>回写用「`export_time is null` 作为更新条件」的原子更新：
     * 两个请求同时点下载时只有一个能改成，另一个拿到 0 行、如实告诉他「已经导出过了」。</p>
     */
    @Transactional
    public ExportDecisionVO consumeById(String tenantCode, long exportId, int rows) {
        DataExport row = dataExportMapper.selectById(exportId);
        if (row == null || Boolean.TRUE.equals(row.getDeleted())
                || tenantCode == null || !tenantCode.equals(row.getTenantCode())) {
            return new ExportDecisionVO(false, "导出申请不存在，或不属于当前企业", false, null);
        }
        if (row.getStatus() == null || row.getStatus() != APPROVED) {
            return new ExportDecisionVO(false, "这条导出申请还没有通过审批", false, null);
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = dataExportMapper.update(null, Wrappers.<DataExport>lambdaUpdate()
                .eq(DataExport::getId, exportId)
                .isNull(DataExport::getExportTime)
                .set(DataExport::getExportRows, rows)
                .set(DataExport::getExportTime, now)
                .set(DataExport::getUpdateTime, now));
        if (updated == 0) {
            return new ExportDecisionVO(false, "这张审批单已经导出过了，请重新提交导出申请", false, null);
        }
        return new ExportDecisionVO(true, "已通过审批（申请单 " + exportId + "）", true,
                String.valueOf(exportId));
    }

    /** 企业侧提交导出申请（customer-service 内部接口调用） */
    @Transactional
    public ExportRequestVO request(String tenantCode, int exportType, String scopeJson,
                                   Long requesterId, String requesterName) {
        if (tenantCode == null || tenantCode.isBlank()) {
            throw new BizException(40001, "缺少租户");
        }
        DataExport pending = dataExportMapper.selectOne(Wrappers.<DataExport>lambdaQuery()
                .eq(DataExport::getTenantCode, tenantCode)
                .eq(DataExport::getExportType, (short) exportType)
                .eq(DataExport::getStatus, PENDING)
                .eq(DataExport::getDeleted, false)
                .last("LIMIT 1"));
        if (pending != null) {
            // 同一类型已有待审批的申请就别重复提，避免平台侧被同一件事刷屏
            return new ExportRequestVO(String.valueOf(pending.getId()), PENDING, true);
        }
        // 空字符串不是合法 JSON：没有筛选条件就存 NULL，别让 PG 报
        // "invalid input syntax for type json ... $4 = ''"
        String scope = scopeJson == null || scopeJson.isBlank() ? null : scopeJson.trim();
        long id = idGenerator.nextId();
        LocalDateTime now = LocalDateTime.now();
        String name = requesterName == null ? "企业用户" : requesterName;
        dataExportMapper.insert(DataExport.builder()
                .id(id)
                .tenantCode(tenantCode)
                .exportType((short) exportType)
                .scopeJson(scope)
                .requesterId(requesterId == null ? 0L : requesterId)
                .status((short) PENDING)
                .requestTime(now)
                .createTime(now)
                .updateTime(now)
                .creator(name)
                .editor(name)
                .deleted(false)
                .build());
        log.info("收到导出申请 tenant={} type={} requester={}", tenantCode, exportType, name);
        return new ExportRequestVO(String.valueOf(id), PENDING, false);
    }

    // ------------------------------------------------------------------ 内部

    private long countByStatus(int status) {
        return dataExportMapper.selectCount(Wrappers.<DataExport>lambdaQuery()
                .eq(DataExport::getDeleted, false)
                .eq(DataExport::getStatus, status));
    }

    private DataExport requireExport(long id) {
        DataExport row = dataExportMapper.selectById(id);
        if (row == null || Boolean.TRUE.equals(row.getDeleted())) {
            throw new BizException(40401, "导出申请不存在：" + id);
        }
        return row;
    }

    private ExportRowVO toVO(DataExport row) {
        return new ExportRowVO(String.valueOf(row.getId()), row.getTenantCode(),
                row.getExportType() == null ? null : row.getExportType().intValue(),
                exportTypeText(row.getExportType()), row.getScopeJson(),
                row.getStatus() == null ? null : row.getStatus().intValue(),
                exportStatusText(row.getStatus()), row.getCreator(), row.getEditor(),
                row.getRejectReason(), text(row.getRequestTime()), text(row.getApproveTime()),
                row.getExportRows(), text(row.getExportTime()));
    }

    private static String exportTypeText(Short type) {
        if (type == null) {
            return "数据";
        }
        return switch (type) {
            case 1 -> "客户档案";
            case 2 -> "会话记录";
            case 3 -> "工单";
            default -> "其它数据";
        };
    }

    private static String exportStatusText(Short status) {
        if (status == null) {
            return "—";
        }
        return switch (status) {
            case 2 -> "已通过";
            case 3 -> "已驳回";
            default -> "待审批";
        };
    }

    private static String text(LocalDateTime time) {
        return time == null ? null : time.format(TIME_FORMAT);
    }
}
