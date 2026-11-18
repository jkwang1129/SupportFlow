package io.supportflow.ops.service;

import io.supportflow.common.api.IdVO;
import io.supportflow.common.audit.AuditLogClient;
import io.supportflow.common.auth.LoginUser;
import io.supportflow.common.exception.BizException;
import io.supportflow.common.id.SnowflakeIdGenerator;
import io.supportflow.ops.dto.FinishBody;
import io.supportflow.ops.dto.FollowUpBody;
import io.supportflow.ops.dto.FollowUpItemVO;
import io.supportflow.ops.dto.FollowUpSummaryVO;
import io.supportflow.ops.dto.FollowUpVO;
import io.supportflow.ops.dto.TaskBody;
import io.supportflow.ops.dto.TaskFinishVO;
import io.supportflow.ops.dto.TaskItemVO;
import io.supportflow.ops.dto.TaskPageVO;
import io.supportflow.ops.dto.TaskSummaryVO;
import io.supportflow.ops.entity.CustomerSuccess;
import io.supportflow.ops.entity.CustomerSuccessTask;
import io.supportflow.ops.mapper.CustomerSuccessMapper;
import io.supportflow.ops.mapper.CustomerSuccessTaskMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 客户成功（平台「客户成功」页）的"人维护的这一半"：随访状态 + 回访任务。

 * <p>健康度、续费风险、扩展商机的**自动判断**在 tenant-service（数据在 tenant_db）；
 * 这里管的是运营决定的事：这家租户谁负责、下次什么时候回访、人工把风险/商机改成了什么、
 * 以及一条条的回访任务（建 → 回访 → 写结论 / 取消）。</p>
 *
 * <p>为什么风险与商机允许人工覆盖：模型只能看到用量与到期，看不到"客户老板刚换了人"
 * 这种事。人工覆盖优先于自动判断，页面上会标明哪一行被人工改过。</p>
 */
@Service
public class CustomerSuccessService {

    private static final Logger log = LoggerFactory.getLogger(CustomerSuccessService.class);

    /** 审计模块名（与平台页筛选器一致） */
    private static final String MODULE = "客户成功";

    /** 任务状态：1-待回访、2-已完成、3-已取消 */
    private static final int TASK_PENDING = 1;
    private static final int TASK_DONE = 2;
    private static final int TASK_CANCELLED = 3;

    /** 列表一次最多 200 行：客户成功是"人工跟进"的活儿，没人会翻到第 20 页 */
    private static final int MAX_PAGE_SIZE = 200;

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final CustomerSuccessMapper successMapper;
    private final CustomerSuccessTaskMapper taskMapper;
    private final SnowflakeIdGenerator idGenerator;
    private final AuditLogClient auditLogClient;

    public CustomerSuccessService(CustomerSuccessMapper successMapper,
                                  CustomerSuccessTaskMapper taskMapper,
                                  SnowflakeIdGenerator idGenerator,
                                  AuditLogClient auditLogClient) {
        this.successMapper = successMapper;
        this.taskMapper = taskMapper;
        this.idGenerator = idGenerator;
        this.auditLogClient = auditLogClient;
    }

    // ------------------------------------------------------------------ 随访状态

    /** 随访状态表：tenantCode → 负责人 / 回访时间 / 人工判断（前端按租户编号合并到健康度列表） */
    public FollowUpVO followUps() {
        List<CustomerSuccess> rows = successMapper.selectList(Wrappers.<CustomerSuccess>lambdaQuery()
                .eq(CustomerSuccess::getDeleted, false)
                .orderByAsc(CustomerSuccess::getTenantCode));
        List<FollowUpItemVO> items = new ArrayList<>(rows.size());
        LocalDate today = LocalDate.now();
        int dueSoon = 0;
        int overdue = 0;
        int manualRisk = 0;
        int manualExpand = 0;
        for (CustomerSuccess row : rows) {
            String nextVisitDate = null;
            Long visitRemainDays = null;
            if (row.getNextVisitTime() != null) {
                LocalDate next = row.getNextVisitTime().toLocalDate();
                long remain = ChronoUnit.DAYS.between(today, next);
                nextVisitDate = next.toString();
                visitRemainDays = remain;
                if (remain < 0) {
                    overdue++;
                } else if (remain <= 3) {
                    dueSoon++;
                }
            }
            if (row.getRenewRisk() != null && row.getRenewRisk() == 3) {
                manualRisk++;
            }
            if (row.getExpandOpportunity() != null && row.getExpandOpportunity() == 1) {
                manualExpand++;
            }
            items.add(new FollowUpItemVO(row.getTenantCode(),
                    row.getCsOwner() == null ? null : String.valueOf(row.getCsOwner()),
                    row.getCsOwnerName(), row.getRenewRisk(), riskText(row.getRenewRisk()),
                    row.getExpandOpportunity(),
                    row.getExpandOpportunity() != null && row.getExpandOpportunity() == 1 ? "有" : "无",
                    row.getHealthScore(), text(row.getLastVisitTime()), text(row.getNextVisitTime()),
                    row.getRemark(), text(row.getUpdateTime()), nextVisitDate, visitRemainDays));
        }
        return new FollowUpVO(new FollowUpSummaryVO(items.size(), dueSoon, overdue, manualRisk,
                manualExpand), items);
    }

    /** 保存随访安排（一个租户一行，upsert）：负责人、下次回访、人工覆盖的风险与商机、备注 */
    @Transactional
    public FollowUpVO saveFollowUp(LoginUser operator, FollowUpBody body) {
        if (body == null || body.tenantCode() == null || body.tenantCode().isBlank()) {
            throw new BizException(40001, "缺少租户编号");
        }
        Integer renewRisk = body.renewRisk();
        if (renewRisk != null && (renewRisk < 1 || renewRisk > 3)) {
            throw new BizException(40001, "续费风险只能是 1-低 / 2-中 / 3-高");
        }
        Integer expand = body.expandOpportunity();
        if (expand != null && expand != 1 && expand != 2) {
            throw new BizException(40001, "扩展商机只能是 1-有 / 2-无");
        }
        LocalDateTime nextVisit = parseTime(body.nextVisitTime());
        LocalDateTime now = LocalDateTime.now();
        String operatorName = operator == null ? "系统" : operator.name();
        String tenantCode = body.tenantCode().trim();

        CustomerSuccess existing = successMapper.selectOne(Wrappers.<CustomerSuccess>lambdaQuery()
                .eq(CustomerSuccess::getTenantCode, tenantCode)
                .eq(CustomerSuccess::getDeleted, false)
                .last("LIMIT 1"));
        if (existing == null) {
            successMapper.insert(CustomerSuccess.builder()
                    .id(idGenerator.nextId())
                    .tenantCode(tenantCode)
                    .healthScore(body.healthScore() == null ? null : body.healthScore().shortValue())
                    .renewRisk((short) (renewRisk == null ? 1 : renewRisk))
                    .expandOpportunity((short) (expand == null ? 2 : expand))
                    .csOwner(body.csOwnerId())
                    .csOwnerName(body.csOwnerName())
                    .nextVisitTime(nextVisit)
                    .remark(body.remark())
                    .createTime(now)
                    .updateTime(now)
                    .creator(operatorName)
                    .editor(operatorName)
                    .deleted(false)
                    .build());
        } else {
            CustomerSuccess update = new CustomerSuccess();
            update.setId(existing.getId());
            update.setHealthScore(body.healthScore() == null ? existing.getHealthScore()
                    : body.healthScore().shortValue());
            update.setRenewRisk(renewRisk == null ? existing.getRenewRisk() : (short) (int) renewRisk);
            update.setExpandOpportunity(expand == null ? existing.getExpandOpportunity() : (short) (int) expand);
            update.setCsOwner(body.csOwnerId() == null ? existing.getCsOwner() : body.csOwnerId());
            update.setCsOwnerName(body.csOwnerName() == null ? existing.getCsOwnerName() : body.csOwnerName());
            update.setNextVisitTime(nextVisit == null ? existing.getNextVisitTime() : nextVisit);
            update.setRemark(body.remark() == null ? existing.getRemark() : body.remark());
            update.setUpdateTime(now);
            update.setEditor(operatorName);
            successMapper.updateById(update);
        }
        auditLogClient.record(operator, MODULE, "保存随访安排", "租户", tenantCode, true,
                "租户 " + tenantCode + " 负责人=" + (body.csOwnerName() == null ? "未指定" : body.csOwnerName())
                        + "，下次回访=" + (nextVisit == null ? "未指定" : nextVisit.format(TIME_FORMAT))
                        + (renewRisk == null ? "" : "，人工续费风险=" + riskText((short) (int) renewRisk))
                        + (expand == null ? "" : "，人工商机=" + (expand == 1 ? "有" : "无")));
        return followUps();
    }

    // ------------------------------------------------------------------ 回访任务

    public TaskPageVO tasks(Integer status, String tenantCode, Integer page, Integer pageSize) {
        int current = page == null || page < 1 ? 1 : page;
        int size = pageSize == null || pageSize < 1 ? 10 : Math.min(pageSize, MAX_PAGE_SIZE);
        String tenant = tenantCode == null || tenantCode.isBlank() ? null : tenantCode.trim();
        var wrapper = Wrappers.<CustomerSuccessTask>lambdaQuery()
                .eq(CustomerSuccessTask::getDeleted, false)
                .eq(status != null && status > 0, CustomerSuccessTask::getStatus, status)
                .eq(tenant != null, CustomerSuccessTask::getTenantCode, tenant);
        long total = taskMapper.selectCount(wrapper);
        List<CustomerSuccessTask> rows = taskMapper.selectList(Wrappers.<CustomerSuccessTask>lambdaQuery()
                .eq(CustomerSuccessTask::getDeleted, false)
                .eq(status != null && status > 0, CustomerSuccessTask::getStatus, status)
                .eq(tenant != null, CustomerSuccessTask::getTenantCode, tenant)
                // 待回访的按到期时间排（谁先到期谁先做），其它按创建时间倒序
                .orderByAsc(CustomerSuccessTask::getStatus)
                .orderByAsc(CustomerSuccessTask::getDueTime)
                .orderByDesc(CustomerSuccessTask::getCreateTime)
                .last("LIMIT " + size + " OFFSET " + (long) (current - 1) * size));

        LocalDate today = LocalDate.now();
        List<TaskItemVO> list = new ArrayList<>(rows.size());
        for (CustomerSuccessTask row : rows) {
            list.add(toTaskVO(row, today));
        }
        return new TaskPageVO((int) total, current, size, list, taskSummary());
    }

    /** 任务概览：待回访 / 逾期 / 今日到期 / 已完成（KPI 用） */
    public TaskSummaryVO taskSummary() {
        List<CustomerSuccessTask> pending = taskMapper.selectList(Wrappers.<CustomerSuccessTask>lambdaQuery()
                .eq(CustomerSuccessTask::getDeleted, false)
                .eq(CustomerSuccessTask::getStatus, TASK_PENDING));
        long done = taskMapper.selectCount(Wrappers.<CustomerSuccessTask>lambdaQuery()
                .eq(CustomerSuccessTask::getDeleted, false)
                .eq(CustomerSuccessTask::getStatus, TASK_DONE));
        LocalDate today = LocalDate.now();
        int overdue = 0;
        int dueToday = 0;
        for (CustomerSuccessTask row : pending) {
            if (row.getDueTime() == null) {
                continue;
            }
            LocalDate due = row.getDueTime().toLocalDate();
            if (due.isBefore(today)) {
                overdue++;
            } else if (due.isEqual(today)) {
                dueToday++;
            }
        }
        return new TaskSummaryVO(pending.size(), overdue, dueToday, (int) done);
    }

    @Transactional
    public IdVO createTask(LoginUser operator, TaskBody body) {
        if (body == null || body.title() == null || body.title().isBlank()) {
            throw new BizException(40001, "请填写回访任务标题");
        }
        if (body.tenantCode() == null || body.tenantCode().isBlank()) {
            throw new BizException(40001, "请选择要回访的租户");
        }
        int type = body.taskType() == null ? 2 : body.taskType();
        if (type < 1 || type > 4) {
            throw new BizException(40001, "任务类型只能是 1-续费 / 2-健康 / 3-扩展 / 4-其它");
        }
        LocalDateTime now = LocalDateTime.now();
        String operatorName = operator == null ? "系统" : operator.name();
        long id = idGenerator.nextId();
        taskMapper.insert(CustomerSuccessTask.builder()
                .id(id)
                .tenantCode(body.tenantCode().trim())
                .companyName(body.companyName())
                .taskType((short) type)
                .title(body.title().trim())
                .ownerId(body.ownerId())
                .ownerName(body.ownerName() == null ? operatorName : body.ownerName())
                .dueTime(parseTime(body.dueTime()))
                .status((short) TASK_PENDING)
                .createTime(now)
                .updateTime(now)
                .creator(operatorName)
                .editor(operatorName)
                .deleted(false)
                .build());
        auditLogClient.record(operator, MODULE, "新建回访任务", "租户", body.tenantCode().trim(), true,
                "给 " + (body.companyName() == null ? body.tenantCode() : body.companyName())
                        + " 建了" + taskTypeText(type) + "回访任务：「" + body.title().trim() + "」");
        return new IdVO(String.valueOf(id));
    }

    @Transactional
    public IdVO updateTask(LoginUser operator, long id, TaskBody body) {
        CustomerSuccessTask existing = requireTask(id);
        if (existing.getStatus() != null && existing.getStatus() != TASK_PENDING) {
            throw new BizException(40001, "任务已结束，改不了了");
        }
        int type = body.taskType() == null ? (existing.getTaskType() == null ? 2 : existing.getTaskType())
                : body.taskType();
        CustomerSuccessTask update = new CustomerSuccessTask();
        update.setId(id);
        update.setTitle(body.title() == null || body.title().isBlank() ? existing.getTitle() : body.title().trim());
        update.setTaskType((short) type);
        update.setOwnerId(body.ownerId() == null ? existing.getOwnerId() : body.ownerId());
        update.setOwnerName(body.ownerName() == null ? existing.getOwnerName() : body.ownerName());
        update.setDueTime(parseTime(body.dueTime()) == null ? existing.getDueTime() : parseTime(body.dueTime()));
        update.setUpdateTime(LocalDateTime.now());
        update.setEditor(operator == null ? "系统" : operator.name());
        taskMapper.updateById(update);
        auditLogClient.record(operator, MODULE, "修改回访任务", "租户", existing.getTenantCode(), true,
                "修改回访任务「" + update.getTitle() + "」");
        return new IdVO(String.valueOf(id));
    }

    /** 完成 / 取消：完成时把回访结论一起写下来（下次别人接手能看懂） */
    @Transactional
    public TaskFinishVO finishTask(LoginUser operator, long id, FinishBody body) {
        CustomerSuccessTask existing = requireTask(id);
        if (existing.getStatus() != null && existing.getStatus() != TASK_PENDING) {
            throw new BizException(40001, "任务已结束");
        }
        int status = body != null && body.status() != null ? body.status() : TASK_DONE;
        if (status != TASK_DONE && status != TASK_CANCELLED) {
            throw new BizException(40001, "只能标记完成（2）或取消（3）");
        }
        LocalDateTime now = LocalDateTime.now();
        CustomerSuccessTask update = new CustomerSuccessTask();
        update.setId(id);
        update.setStatus((short) status);
        update.setResult(body == null ? null : body.result());
        update.setFinishTime(now);
        update.setUpdateTime(now);
        update.setEditor(operator == null ? "系统" : operator.name());
        taskMapper.updateById(update);

        // 完成一次回访顺手更新随访状态里的"上次回访时间"，省得运营两头填
        if (status == TASK_DONE) {
            CustomerSuccess follow = successMapper.selectOne(Wrappers.<CustomerSuccess>lambdaQuery()
                    .eq(CustomerSuccess::getTenantCode, existing.getTenantCode())
                    .eq(CustomerSuccess::getDeleted, false)
                    .last("LIMIT 1"));
            if (follow != null) {
                CustomerSuccess touch = new CustomerSuccess();
                touch.setId(follow.getId());
                touch.setLastVisitTime(now);
                touch.setUpdateTime(now);
                touch.setEditor(operator == null ? "系统" : operator.name());
                successMapper.updateById(touch);
            }
        }
        auditLogClient.record(operator, MODULE,
                status == TASK_DONE ? "完成回访任务" : "取消回访任务",
                "租户", existing.getTenantCode(), true,
                (status == TASK_DONE ? "完成" : "取消") + "回访任务「" + existing.getTitle() + "」"
                        + (body == null || body.result() == null || body.result().isBlank()
                        ? "" : "，结论：" + body.result()));
        return new TaskFinishVO(String.valueOf(id), status);
    }

    // ------------------------------------------------------------------ 内部

    private CustomerSuccessTask requireTask(long id) {
        CustomerSuccessTask task = taskMapper.selectById(id);
        if (task == null || Boolean.TRUE.equals(task.getDeleted())) {
            throw new BizException(40401, "回访任务不存在：" + id);
        }
        return task;
    }

    private TaskItemVO toTaskVO(CustomerSuccessTask row, LocalDate today) {
        short taskType = row.getTaskType() == null ? 2 : row.getTaskType();
        short status = row.getStatus() == null ? TASK_PENDING : row.getStatus();
        Long dueRemainDays = null;
        boolean overdue = false;
        boolean pending = row.getStatus() != null && row.getStatus() == TASK_PENDING;
        if (pending && row.getDueTime() != null) {
            long remain = ChronoUnit.DAYS.between(today, row.getDueTime().toLocalDate());
            dueRemainDays = remain;
            overdue = remain < 0;
        }
        return new TaskItemVO(String.valueOf(row.getId()), row.getTenantCode(), row.getCompanyName(),
                taskType, taskTypeText(taskType), row.getTitle(), row.getOwnerName(),
                text(row.getDueTime()), status, taskStatusText(status), row.getResult(),
                text(row.getFinishTime()), text(row.getCreateTime()), dueRemainDays, overdue);
    }

    private static String taskTypeText(int type) {
        return switch (type) {
            case 1 -> "续费";
            case 3 -> "扩展";
            case 4 -> "其它";
            default -> "健康";
        };
    }

    private static String taskStatusText(int status) {
        return switch (status) {
            case 2 -> "已完成";
            case 3 -> "已取消";
            default -> "待回访";
        };
    }

    private static String riskText(Short risk) {
        if (risk == null) {
            return "—";
        }
        return switch (risk) {
            case 3 -> "高";
            case 2 -> "中";
            default -> "低";
        };
    }

    private static String text(LocalDateTime time) {
        return time == null ? null : time.format(TIME_FORMAT);
    }

    private static LocalDateTime parseTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim().replace('T', ' ');
        try {
            if (text.length() <= 10) {
                return LocalDate.parse(text).atStartOfDay();
            }
            return LocalDateTime.parse(text.length() > 19 ? text.substring(0, 19) : text,
                    TIME_FORMAT);
        } catch (Exception e) {
            log.warn("时间格式解析失败，已忽略：{}", value);
            return null;
        }
    }
}
