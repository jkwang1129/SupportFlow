package io.supportflow.common.audit;

/**
 * 一条审计日志（写入端与 ops-service 的共同契约）。
 *
 * <p>{@code auditTime} 不在这里传：由 ops-service 落库时取服务端时间，
 * 免得多台机器的时钟不一致，也省掉一个字段。</p>
 *
 * @param tenantCode   操作人所属租户（平台操作为 PLATFORM）
 * @param operatorId   操作人 ID（定时任务等无操作人的场景为 null）
 * @param operatorName 操作人姓名（写日志时的快照）
 * @param module       模块：企业审核 / 用户管理 / 角色权限 / 计费与账单 / 租户管理 / …
 * @param action       操作：通过企业审核 / 停用账号 / 保存角色权限 / 结算账单 …
 * @param targetType   对象类型：企业 / 账号 / 角色 / 租户 / 账单
 * @param targetId     对象标识（编码或编号，便于顺着日志找到那条数据）
 * @param result       结果码：1-成功、2-失败
 * @param detail       详情（人话描述，写清"对谁做了什么、结果如何"）
 * @param ip           来源 IP
 * @param requestId    请求号
 */
public record AuditLogRecord(
        String tenantCode,
        Long operatorId,
        String operatorName,
        String module,
        String action,
        String targetType,
        String targetId,
        Integer result,
        String detail,
        String ip,
        String requestId
) {
}
