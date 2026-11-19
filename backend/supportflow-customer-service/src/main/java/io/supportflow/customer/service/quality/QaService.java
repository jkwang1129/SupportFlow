package io.supportflow.customer.service.quality;

import io.supportflow.common.context.TenantContext;
import io.supportflow.common.auth.LoginUser;
import io.supportflow.common.exception.BizException;
import io.supportflow.common.id.SnowflakeIdGenerator;
import io.supportflow.customer.dto.quality.BatchAiResult;
import io.supportflow.customer.dto.quality.BatchReviewResult;
import io.supportflow.customer.dto.quality.EvaluateRequest;
import io.supportflow.customer.dto.quality.EvaluateResult;
import io.supportflow.customer.dto.quality.OverviewVO;
import io.supportflow.customer.dto.quality.RuleResultVO;
import io.supportflow.customer.dto.quality.RuleSpec;
import io.supportflow.customer.dto.quality.RuleVO;
import io.supportflow.customer.dto.quality.ScanResult;
import io.supportflow.customer.dto.quality.TaskDetailVO;
import io.supportflow.customer.dto.quality.TaskVO;
import io.supportflow.customer.entity.QaAlert;
import io.supportflow.customer.entity.QaReview;
import io.supportflow.customer.entity.QaRule;
import io.supportflow.customer.entity.QaTask;
import io.supportflow.customer.entity.Customer;
import io.supportflow.customer.entity.Session;
import io.supportflow.customer.entity.SessionMessage;
import io.supportflow.customer.internal.QaAiClient;
import io.supportflow.customer.internal.UserNameClient;
import io.supportflow.customer.mapper.QaReviewMapper;
import io.supportflow.customer.mapper.QaAlertMapper;
import io.supportflow.customer.mapper.QaRuleMapper;
import io.supportflow.customer.mapper.CustomerMapper;
import io.supportflow.customer.mapper.QaTaskMapper;
import io.supportflow.customer.mapper.SessionMapper;
import io.supportflow.customer.mapper.SessionMessageMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 质检中心：规则配置、任务列表、AI 全量扫描、人工复核。
 */
@Service
public class QaService {

    private static final Logger log = LoggerFactory.getLogger(QaService.class);

    private static final DateTimeFormatter TASK_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 送检对话的最大消息条数，避免超长会话把提示词撑爆 */
    private static final int MAX_DIALOG_MESSAGES = 60;

    /** 一次"发起全量质检"最多补扫多少条已结束会话 */
    private static final int SCAN_LIMIT = 50;

    /** 一条质检任务最多带多少条实时预警作为证据 */
    private static final int MAX_ALERT_EVIDENCE = 50;

    /**
     * AI 初检的线程池：会话结束是交互动作，不能卡在调模型上。
     * 单线程就够了——质检是后台活，串行跑还顺带避免了打爆 AI 服务。
     */
    private static final ExecutorService AI_EVALUATOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "qa-ai-evaluate");
        thread.setDaemon(true);
        return thread;
    });

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 默认规则：名称、类型码、类型文案、检查内容 */
    private static final RuleSeed[] DEFAULT_RULES = {
            new RuleSeed("敏感词与禁语", 1, "敏感词", "排查“妈的、废物、随便你”等不礼貌用语"),
            new RuleSeed("服务承诺规范", 2, "承诺规范", "承诺必须包含明确时间与可兑现口径"),
            new RuleSeed("必答项完整", 3, "必答项", "订单号、地址、发票抬头等关键信息必须核实"),
            new RuleSeed("情绪安抚", 4, "情绪识别", "客户表达不满时应先致歉再安抚，禁止机械回复"),
            new RuleSeed("响应超时", 5, "响应超时", "客户发出消息后长时间没有坐席回复，需要提醒并及时响应"),
    };

    private final QaRuleMapper qaRuleMapper;
    private final QaTaskMapper qaTaskMapper;
    private final QaReviewMapper qaReviewMapper;
    private final SessionMessageMapper sessionMessageMapper;
    private final QaAlertMapper qaAlertMapper;
    private final SessionMapper sessionMapper;
    private final CustomerMapper customerMapper;
    private final UserNameClient userNameClient;
    private final QaAiClient qaAiClient;
    private final SnowflakeIdGenerator idGenerator;

    public QaService(
            QaRuleMapper qaRuleMapper,
            QaTaskMapper qaTaskMapper,
            QaReviewMapper qaReviewMapper,
            SessionMessageMapper sessionMessageMapper,
            QaAlertMapper qaAlertMapper,
            SessionMapper sessionMapper,
            CustomerMapper customerMapper,
            UserNameClient userNameClient,
            QaAiClient qaAiClient,
            SnowflakeIdGenerator idGenerator
    ) {
        this.qaRuleMapper = qaRuleMapper;
        this.qaTaskMapper = qaTaskMapper;
        this.qaReviewMapper = qaReviewMapper;
        this.sessionMessageMapper = sessionMessageMapper;
        this.qaAlertMapper = qaAlertMapper;
        this.sessionMapper = sessionMapper;
        this.customerMapper = customerMapper;
        this.userNameClient = userNameClient;
        this.qaAiClient = qaAiClient;
        this.idGenerator = idGenerator;
    }

    /**
     * 看板概览。
     */
    public OverviewVO overview(LoginUser user) {
        String tenant = tenantOf(user);
        ensureDemoData(tenant, user.userId());
        List<QaTask> tasks = qaTaskMapper.selectList(
                Wrappers.<QaTask>lambdaQuery()
                        .eq(QaTask::getTenantCode, tenant)
                        .eq(QaTask::getDeleted, false));
        int total = tasks.size();
        int pending = countStatus(tasks, 1);
        int passed = countStatus(tasks, 2);
        int rejected = countStatus(tasks, 3);
        double avgAi = average(tasks.stream().map(QaTask::getAiScore).toList());
        double avgReview = average(tasks.stream()
                .filter(t -> t.getReviewScore() != null)
                .map(QaTask::getReviewScore).toList());
        int riskLow = countRisk(tasks, 1);
        int riskMid = countRisk(tasks, 2);
        int riskHigh = countRisk(tasks, 3);
        double passRate = total == 0 ? 0 : round2(passed * 100.0 / total);
        // 实时预警三个数：总告警、待处理、严重
        Map<String, Object> alertRow = qaAlertMapper.selectAlertStat(tenant);
        int alertTotal = longToInt(alertRow == null ? null : alertRow.get("total"));
        int alertPending = longToInt(alertRow == null ? null : alertRow.get("pending"));
        int alertSevere = longToInt(alertRow == null ? null : alertRow.get("severe"));
        return new OverviewVO(total, pending, passed, rejected, round2(avgAi), round2(avgReview),
                passRate, riskLow, riskMid, riskHigh, alertTotal, alertPending, alertSevere);
    }

    private int longToInt(Object value) {
        if (value == null) {
            return 0;
        }
        return value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value));
    }

    /**
     * 任务列表。
     */
    public List<TaskVO> tasks(LoginUser user, Integer status, String keyword) {
        String tenant = tenantOf(user);
        ensureDemoData(tenant, user.userId());
        List<QaTask> rows = qaTaskMapper.selectList(
                Wrappers.<QaTask>lambdaQuery()
                        .eq(QaTask::getTenantCode, tenant)
                        .eq(QaTask::getDeleted, false)
                        .eq(status != null, QaTask::getStatus, status)
                        .orderByDesc(QaTask::getCreateTime));
        String kw = keyword == null ? "" : keyword.trim().toLowerCase();
        return rows.stream()
                .filter(t -> kw.isBlank() || matches(t, kw))
                .map(this::toTaskVO)
                .toList();
    }

    /**
     * 任务详情（含规则评分明细）。
     */
    public TaskDetailVO detail(LoginUser user, String taskNo) {
        String tenant = tenantOf(user);
        QaTask task = requireTask(tenant, taskNo);
        AiResult ai = parseAi(task);
        List<RuleResultVO> results = new ArrayList<>();
        List<QaRule> rules = enabledRules(tenant);
        for (QaRule rule : rules) {
            boolean fail = ai.rules().contains(rule.getRuleName());
            results.add(new RuleResultVO(
                    rule.getRuleName(),
                    typeText(rule.getRuleType()),
                    !fail,
                    fail ? "AI 标记命中：关注 " + rule.getRuleContent() : "未命中该规则风险项"
            ));
        }
        return new TaskDetailVO(
                String.valueOf(task.getId()),
                task.getTaskNo(),
                ai.sessionName(),
                ai.agentName(),
                number(task.getAiScore()),
                number(task.getReviewScore()),
                task.getRiskLevel(),
                riskText(task.getRiskLevel()),
                task.getStatus(),
                statusText(task.getStatus()),
                task.getCreateTime() == null ? null : task.getCreateTime().toString(),
                task.getReviewTime() == null ? null : task.getReviewTime().toString(),
                ai.comment(),
                ai.rules(),
                results,
                task.getReviewerId() == null ? null : String.valueOf(task.getReviewerId()),
                ai.alerts(),
                ai.alerts().size(),
                severeCount(ai)
        );
    }

    /** 实时预警里"严重"级的条数 */
    private int severeCount(AiResult ai) {
        if (ai.alerts() == null) {
            return 0;
        }
        int count = 0;
        for (Map<String, Object> item : ai.alerts()) {
            Object severity = item.get("severity");
            if (severity != null && Integer.parseInt(String.valueOf(severity)) >= 3) {
                count++;
            }
        }
        return count;
    }


    /**
     * 发起全量质检：扫描"已结束、还没质检过"的**真实会话**，逐条生成待复核任务并跑 AI 初检。
     *
     * <p>早先这版是从内置样例池随机挑 3 条插进去的，等于质检中心里看不到自己客户的会话。
     * 现在改成扫真实会话：会话一结束系统也会自动建一条任务，这里是把历史漏掉的补上。</p>
     */
    @Transactional
    public ScanResult scan(LoginUser user) {
        String tenant = tenantOf(user);
        ensureRules(tenant, user.userId());
        List<Session> candidates = sessionMapper.selectClosedSessionsWithoutTask(tenant, SCAN_LIMIT);
        List<String> taskNos = new ArrayList<>();
        for (Session session : candidates) {
            String taskNo = createTaskFromSession(tenant, session, "AI_QA");
            if (taskNo == null) {
                continue;
            }
            taskNos.add(taskNo);
            // 补扫是用户守着进度条点的，这里同步把 AI 初检跑完
            evaluateNow(tenant, taskNo);
        }
        log.info("全量质检完成 tenant={} 扫描已结束会话={} 生成任务={}",
                tenant, candidates.size(), taskNos.size());
        return new ScanResult(candidates.size(), taskNos.size(), taskNos);
    }

    /**
     * 会话结束时自动建一条质检任务，并把真实对话送去 AI 初检。
     *
     * <p>AI 是慢操作（要调大模型），所以放后台线程跑：坐席点"结束会话"要立刻有反馈，
     * 不能卡在模型上。任务先以"待初检"落库，几秒后分数和结论自己补上。</p>
     *
     * @return 生成的任务号；会话没说过话、或已经有任务时返回 null
     */
    public String createFromSession(String tenantCode, Session session, Long operatorId) {
        String taskNo = createTaskFromSession(tenantCode, session,
                operatorId == null ? "SYSTEM" : String.valueOf(operatorId));
        if (taskNo != null) {
            evaluateAsync(tenantCode, taskNo);
        }
        return taskNo;
    }

    /** 后台跑 AI 初检：失败只记日志，任务还留在"待复核"里等人工处理 */
    private void evaluateAsync(String tenantCode, String taskNo) {
        AI_EVALUATOR.submit(() -> {
            try {
                TenantContext.run(tenantCode, () -> evaluateNow(tenantCode, taskNo));
            } catch (Exception e) {
                log.warn("后台 AI 初检失败 tenant={} taskNo={} error={}", tenantCode, taskNo, e.getMessage());
            }
        });
    }

    /** 跑一次 AI 初检并把结果写回任务 */
    private void evaluateNow(String tenantCode, String taskNo) {
        QaTask task = qaTaskMapper.selectOne(Wrappers.<QaTask>lambdaQuery()
                .eq(QaTask::getTenantCode, tenantCode)
                .eq(QaTask::getTaskNo, taskNo)
                .eq(QaTask::getDeleted, false)
                .last("LIMIT 1"));
        if (task == null) {
            return;
        }
        applyAiEvaluation(tenantCode, task);
        qaTaskMapper.updateById(task);
    }

    /**
     * 由真实会话生成质检任务。
     *
     * <p>两道闸：没有对话内容的不建（客户进来就关的单子没得质检）；
     * 同一会话只建一条（靠 (tenant_code, session_id) 唯一索引兜底）。</p>
     *
     */
    @Transactional
    public String createTaskFromSession(String tenantCode, Session session, String creator) {
        if (session == null || session.getId() == null) {
            return null;
        }
        long exists = qaTaskMapper.selectCount(Wrappers.<QaTask>lambdaQuery()
                .eq(QaTask::getTenantCode, tenantCode)
                .eq(QaTask::getSessionId, session.getId())
                .eq(QaTask::getDeleted, false));
        if (exists > 0) {
            return null;
        }
        String dialog = loadRealDialog(tenantCode, session.getId());
        if (dialog == null) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        // 会话期间弹过的实时预警：作为质检证据一起落库，复核的人要看到"当时到底提醒了什么"
        List<Map<String, Object>> alerts = sessionAlerts(tenantCode, session.getSessionNo());
        Map<String, Object> ai = new LinkedHashMap<>();
        ai.put("sessionName", sessionNameOf(tenantCode, session));
        ai.put("agentName", agentNameOf(session.getAgentId()));
        ai.put("comment", "会话已结束，AI 初检排队中…");
        ai.put("dialog", dialog);
        ai.put("alerts", alerts);

        QaTask task = QaTask.builder()
                .id(idGenerator.nextId())
                .tenantCode(tenantCode)
                .taskNo(generateTaskNo(tenantCode))
                .sessionId(session.getId())
                .agentId(session.getAgentId())
                .aiScore(BigDecimal.valueOf(0).setScale(2, RoundingMode.HALF_UP))
                .aiResult(writeJson(ai))
                .riskLevel(1)
                .status(1)
                .creator(creator)
                .createTime(now)
                .updateTime(now)
                .deleted(false)
                .build();
        try {
            qaTaskMapper.insert(task);
        } catch (DuplicateKeyException e) {
            // 并发下同一个会话被两个入口同时建任务，唯一索引挡住，直接跳过
            log.info("会话已有质检任务，跳过 tenant={} sessionId={}", tenantCode, session.getId());
            return null;
        }
        log.info("已按真实会话生成质检任务 tenant={} sessionNo={} taskNo={}",
                tenantCode, session.getSessionNo(), task.getTaskNo());
        return task.getTaskNo();
    }

    /**
     * 会话期间的实时预警（作为质检证据）。
     *
     * <p>实时质检是"边说边把关"，事后质检是"整体打分"。两边数据打通之后，
     * 复核的人既能看到完整对话，也能看到当时系统在哪些地方弹过提醒、坐席有没有理会。</p>
     */
    private List<Map<String, Object>> sessionAlerts(String tenantCode, String sessionNo) {
        if (sessionNo == null || sessionNo.isBlank()) {
            return List.of();
        }
        List<QaAlert> rows = qaAlertMapper.selectAlerts(
                tenantCode, null, null, null, sessionNo, null, null, null, MAX_ALERT_EVIDENCE);
        List<Map<String, Object>> result = new ArrayList<>();
        for (QaAlert row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", String.valueOf(row.getId()));
            item.put("ruleName", row.getRuleName());
            item.put("ruleType", row.getRuleType());
            item.put("severity", row.getSeverity());
            item.put("severityText", alertSeverityText(row.getSeverity()));
            item.put("hitKeyword", row.getHitKeyword());
            item.put("snippet", row.getSnippet());
            item.put("advice", row.getAdvice());
            item.put("status", row.getStatus());
            item.put("time", row.getCreateTime() == null ? null : row.getCreateTime().toString().replace('T', ' '));
            result.add(item);
        }
        return result;
    }

    private String alertSeverityText(Integer severity) {
        int value = severity == null ? 2 : severity;
        return switch (value) {
            case 3 -> "严重";
            case 1 -> "提示";
            default -> "警告";
        };
    }

    /**
     * 把实时预警拼成送检文本的一段，让 AI 也看到"当时弹过什么提醒"。
     */
    private String buildAlertEvidence(AiResult ai) {
        if (ai.alerts() == null || ai.alerts().isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder("【会话期间实时质检命中】共 ")
                .append(ai.alerts().size()).append(" 条：\n");
        int index = 1;
        for (Map<String, Object> item : ai.alerts()) {
            text.append(index++).append(". ")
                    .append(item.get("time") == null ? "" : item.get("time") + " ")
                    .append("[").append(item.get("severityText")).append("] ")
                    .append(item.get("ruleName"));
            if (item.get("hitKeyword") != null) {
                text.append("（命中词：").append(item.get("hitKeyword")).append("）");
            }
            text.append("  片段：").append(item.get("snippet") == null ? "" : item.get("snippet"));
            text.append(item.get("status") != null && Integer.parseInt(String.valueOf(item.get("status"))) == 2
                    ? "  [坐席已处理]" : "  [未处理]");
            text.append('\n');
        }
        return text.toString();
    }

    /**
     * 风险下限：会话里出现过严重级实时预警的，事后质检不能判成"低风险"。
     *
     * <p>AI 有时只看到对话不会给高风险，但"当时系统已经弹过严重提醒"是硬证据。</p>
     */
    private int alertRiskFloor(AiResult ai) {
        if (ai.alerts() == null || ai.alerts().isEmpty()) {
            return 1;
        }
        int risk = 2;
        for (Map<String, Object> item : ai.alerts()) {
            Object severity = item.get("severity");
            if (severity != null && Integer.parseInt(String.valueOf(severity)) >= 3) {
                return 3;
            }
        }
        return risk;
    }

    /** 任务标题：客户名 + 会话号，一眼能对上是哪一单 */
    private String sessionNameOf(String tenantCode, Session session) {
        Customer customer = session.getCustomerId() == null
                ? null : customerMapper.selectById(session.getCustomerId());
        String name = customer == null || customer.getName() == null || customer.getName().isBlank()
                ? "访客" : customer.getName();
        return name + " · " + session.getSessionNo();
    }

    /** 被检坐席姓名（拿不到就退回用户 ID，别让列表空着） */
    private String agentNameOf(Long agentId) {
        if (agentId == null) {
            return "未接入坐席";
        }
        String key = String.valueOf(agentId);
        Map<String, String> names = userNameClient.namesOf(List.of(key));
        return names.getOrDefault(key, key);
    }

    /**
     * 新增单个质检：针对某个会话手动创建一条待复核任务。
     */
    @Transactional
    public TaskVO createManual(
            LoginUser user,
            String sessionName,
            String agentName,
            Double aiScore,
            Integer riskLevel,
            String comment,
            List<String> ruleNames
    ) {
        String tenant = tenantOf(user);
        ensureRules(tenant, user.userId());
        if (sessionName == null || sessionName.isBlank()) {
            throw new BizException(40001, "请填写质检会话名称");
        }
        if (agentName == null || agentName.isBlank()) {
            throw new BizException(40001, "请填写接待客服");
        }
        int risk = riskLevel == null ? 1 : riskLevel;
        if (risk < 1 || risk > 3) {
            throw new BizException(40001, "风险级别不正确");
        }
        double score = aiScore == null ? 80 : Math.max(0, Math.min(100, aiScore));
        List<String> validRules = rules(user).stream()
                .map(RuleVO::ruleName)
                .filter(name -> ruleNames != null && ruleNames.contains(name))
                .toList();

        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> ai = new LinkedHashMap<>();
        ai.put("sessionName", sessionName.trim());
        ai.put("agentName", agentName.trim());
        ai.put("comment", comment == null || comment.isBlank() ? "人工发起单个质检，请复核人结合会话内容给出结论。" : comment.trim());
        ai.put("rules", validRules);

        QaTask task = QaTask.builder()
                .id(idGenerator.nextId())
                .tenantCode(tenant)
                .taskNo(generateTaskNo(tenant))
                .sessionId(idGenerator.nextId())
                .agentId(null)
                .aiScore(BigDecimal.valueOf(score).setScale(2, RoundingMode.HALF_UP))
                .aiResult(writeJson(ai))
                .riskLevel(risk)
                .status(1)
                .creator(String.valueOf(user.userId()))
                .createTime(now)
                .updateTime(now)
                .deleted(false)
                .build();
        qaTaskMapper.insert(task);
        log.info("人工新增单个质检 tenant={}, taskNo={}, creator={}", tenant, task.getTaskNo(), user.userId());
        return toTaskVO(task);
    }

    /**
     * 人工复核：1-通过、2-驳回、3-重检。
     */
    @Transactional
    public TaskVO review(LoginUser user, String taskNo, int action, Double score, String comment) {
        String tenant = tenantOf(user);
        QaTask task = requireTask(tenant, taskNo);
        LocalDateTime now = LocalDateTime.now();
        if (action == 1 || action == 2) {
            if (score == null || score < 0 || score > 100) {
                throw new BizException(40001, "复核评分必须在 0~100 之间");
            }
            if (action == 2 && (comment == null || comment.isBlank())) {
                throw new BizException(40001, "驳回时必须填写复核意见");
            }
            task.setStatus(action == 1 ? 2 : 3);
            task.setReviewScore(BigDecimal.valueOf(score).setScale(2, RoundingMode.HALF_UP));
            task.setReviewerId(user.userId());
            task.setReviewTime(now);
        } else if (action == 3) {
            task.setStatus(1);
            task.setReviewScore(null);
            task.setReviewerId(null);
            task.setReviewTime(null);
        } else {
            throw new BizException(40001, "复核动作不支持");
        }
        task.setEditor(String.valueOf(user.userId()));
        task.setUpdateTime(now);
        qaTaskMapper.updateById(task);

        qaReviewMapper.insert(QaReview.builder()
                .id(idGenerator.nextId())
                .tenantCode(tenant)
                .taskId(task.getId())
                .reviewerId(user.userId())
                .action(action)
                .comment(comment == null || comment.isBlank() ? null : comment.trim())
                .reviewTime(now)
                .build());
        return toTaskVO(task);
    }

    /**
     * 批量复核：多条任务一次性通过/驳回/重检。
     */
    @Transactional
    public BatchReviewResult batchReview(
            LoginUser user,
            List<String> taskNos,
            int action,
            Double score,
            String comment
    ) {
        if (taskNos == null || taskNos.isEmpty()) {
            throw new BizException(40001, "请选择至少一条质检任务");
        }
        if (taskNos.size() > 200) {
            throw new BizException(40001, "单次批量质检最多选择 200 条任务");
        }
        for (String taskNo : taskNos) {
            review(user, taskNo, action, score, comment);
        }
        return new BatchReviewResult(taskNos.size(), List.copyOf(taskNos));
    }

    /**
     * 批量 AI 质检：对选中的任务重新执行 AI/规则初检，并回到待复核队列。
     */
    @Transactional
    public BatchAiResult batchAiCheck(LoginUser user, List<String> taskNos) {
        String tenant = tenantOf(user);
        if (taskNos == null || taskNos.isEmpty()) {
            throw new BizException(40001, "请选择至少一条质检任务");
        }
        if (taskNos.size() > 200) {
            throw new BizException(40001, "单次批量 AI 质检最多选择 200 条任务");
        }
        List<QaRule> enabled = enabledRules(tenant);
        LocalDateTime now = LocalDateTime.now();
        for (String taskNo : taskNos) {
            QaTask task = requireTask(tenant, taskNo);
            applyAiEvaluation(tenant, task);
            task.setStatus(1);
            task.setReviewScore(null);
            task.setReviewerId(null);
            task.setReviewTime(null);
            task.setEditor(String.valueOf(user.userId()));
            task.setUpdateTime(now);
            qaTaskMapper.updateById(task);
        }
        return new BatchAiResult(taskNos.size(), List.copyOf(taskNos));
    }

    private void applyAiEvaluation(String tenant, QaTask task) {
        AiResult old = parseAi(task);
        List<QaRule> enabled = enabledRules(tenant);
        List<String> ruleNames = enabled.stream().map(QaRule::getRuleName).toList();
        List<RuleSpec> ruleSpecs = enabled.stream()
                .map(rule -> new RuleSpec(rule.getRuleName(), rule.getRuleContent()))
                .toList();
        String realDialog = loadRealDialog(tenant, task.getSessionId());
        String dialog = realDialog != null ? realDialog : effectiveSampleDialog(old);
        // 真实会话记录自带表头，示例数据则拼「摘要 + 对话」
        String transcript = realDialog != null ? realDialog : buildSampleTranscript(old, dialog);
        // 实时预警作为证据一起送检，AI 能据此判断"提醒过还没改"
        String evidence = buildAlertEvidence(old);
        if (!evidence.isBlank()) {
            transcript = evidence + "\n" + transcript;
        }
        EvaluateResult aiResult = qaAiClient.evaluate(new EvaluateRequest(
                tenant,
                old.sessionName(),
                old.agentName(),
                transcript,
                ruleNames,
                ruleSpecs
        ));
        int score = aiResult.aiScore();
        // 实时预警抬高风险下限：弹过严重提醒的会话不能判成低风险
        int risk = Math.max(aiResult.riskLevel(), alertRiskFloor(old));
        List<String> hitRules = aiResult.rules() == null ? List.of() : aiResult.rules();
        Map<String, Object> ai = new LinkedHashMap<>();
        ai.put("sessionName", old.sessionName());
        ai.put("agentName", old.agentName());
        ai.put("comment", aiResult.comment() == null || aiResult.comment().isBlank()
                ? "AI 质检完成，请人工抽样复核确认。"
                : aiResult.comment());
        ai.put("rules", hitRules);
        ai.put("source", aiResult.source());
        // 落库对话内容：后续批量质检、复核时复用同一份会话记录，历史任务也顺带回填
        if (dialog != null && !dialog.isBlank()) {
            ai.put("dialog", dialog);
        }
        task.setAiScore(BigDecimal.valueOf(score).setScale(2, RoundingMode.HALF_UP));
        task.setAiResult(writeJson(ai));
        task.setRiskLevel(risk);
    }

    /**
     * 组装示例任务的送检内容：会话摘要 + 对话记录。
     */
    private String buildSampleTranscript(AiResult sample, String dialog) {
        StringBuilder transcript = new StringBuilder()
                .append("会话场景：").append(sample.sessionName())
                .append("\n接待客服：").append(sample.agentName())
                .append("\n会话摘要：").append(sample.comment());
        if (dialog != null && !dialog.isBlank()) {
            transcript.append("\n\n【对话记录】\n").append(dialog);
        }
        return transcript.toString();
    }

    /**
     * 取示例数据的对话内容。
     *
     * <p>历史任务（早于"记录对话内容"版本创建的）里没有存 dialog，这里按会话名从示例池回填，
     * 否则 AI 只能拿到一句摘要，会直接回"会话记录缺失实际对话内容"。</p>
     */
    private String effectiveSampleDialog(AiResult sample) {
        if (sample.dialog() != null && !sample.dialog().isBlank()) {
            return sample.dialog();
        }
        for (SampleSpec spec : SAMPLE_POOL) {
            if (spec.sessionName().equals(sample.sessionName())) {
                log.info("质检任务缺少对话记录，已按示例会话回填 session={}", sample.sessionName());
                return spec.dialog();
            }
        }
        return null;
    }

    /**
     * 读取真实会话对话；没有真实消息（或会话为空）时返回 null。
     */
    private String loadRealDialog(String tenant, Long sessionId) {
        if (sessionId == null) {
            return null;
        }
        List<SessionMessage> messages = sessionMessageMapper.selectDialog(tenant, sessionId, MAX_DIALOG_MESSAGES);
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        StringBuilder dialog = new StringBuilder("【真实会话记录】\n");
        for (SessionMessage message : messages) {
            dialog.append(senderLabel(message.getSenderType()))
                    .append('：')
                    .append(messageText(message.getContent()))
                    .append('\n');
        }
        return dialog.toString();
    }

    private String senderLabel(Integer senderType) {
        if (senderType == null) {
            return "未知";
        }
        return switch (senderType) {
            case 1 -> "客户";
            case 2 -> "客服";
            case 3 -> "机器人";
            default -> "系统";
        };
    }

    /**
     * 消息内容可能是纯文本，也可能是 {"text": "..."} 之类的 JSON 结构。
     */
    private String messageText(String content) {
        if (content == null || content.isBlank()) {
            return "[非文本消息]";
        }
        String text = content.trim();
        if (text.startsWith("{")) {
            try {
                Map<String, Object> map = JSON.readValue(text, new TypeReference<Map<String, Object>>() {
                });
                // 图片消息：送检的不是那串 JSON，而是"图片 + 识别出来的文字"——
                // 质检要判的正是图里的内容（比如截图里那句违规话术），原样丢 JSON 进去模型也读不懂
                if (map.containsKey("url") || map.containsKey("fileId")) {
                    StringBuilder image = new StringBuilder("[图片]");
                    appendIfPresent(image, map.get("aiSummary"), "判读：");
                    appendIfPresent(image, map.get("aiErrorText"), "报错：");
                    appendIfPresent(image, map.get("aiOcrText"), "图中文字：");
                    return image.toString();
                }
                Object value = map.containsKey("text") ? map.get("text") : map.get("content");
                if (value != null) {
                    return String.valueOf(value);
                }
            } catch (Exception ignored) {
                // 不是标准 JSON，按纯文本处理
            }
        }
        return text;
    }
    /** 拼"[标签] 值"（值为空就跳过），给图片消息的送检文本用 */
    private void appendIfPresent(StringBuilder builder, Object value, String label) {
        if (value == null) {
            return;
        }
        String text = String.valueOf(value).replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) {
            return;
        }
        builder.append(' ').append(label).append(text.length() > 300 ? text.substring(0, 300) + "…" : text);
    }

    /**
     * 规则列表。
     */
    public List<RuleVO> rules(LoginUser user) {
        String tenant = tenantOf(user);
        ensureRules(tenant, user.userId());
        return qaRuleMapper.selectList(
                        Wrappers.<QaRule>lambdaQuery()
                                .eq(QaRule::getTenantCode, tenant)
                                .eq(QaRule::getDeleted, false)
                                .orderByAsc(QaRule::getRuleType)
                                .orderByDesc(QaRule::getCreateTime))
                .stream().map(this::toRuleVO).toList();
    }

    /**
     * 新建/编辑规则。
     */
    @Transactional
    public RuleVO saveRule(LoginUser user, String id, String ruleName, int ruleType,
                           String ruleContent, int weight, boolean enabled,
                           boolean realtime, String hitKeywords, Integer severity,
                           Integer timeoutSeconds) {
        String tenant = tenantOf(user);
        if (ruleType < 1 || ruleType > 5) {
            throw new BizException(40001, "规则类型不正确");
        }
        if (severity != null && (severity < 1 || severity > 3)) {
            throw new BizException(40001, "告警级别取值 1-提示、2-警告、3-严重");
        }
        if (timeoutSeconds != null && (timeoutSeconds < 10 || timeoutSeconds > 3600)) {
            throw new BizException(40001, "响应超时秒数应在 10~3600 之间");
        }
        weight = Math.max(0, Math.min(100, weight));
        LocalDateTime now = LocalDateTime.now();
        QaRule rule;
        if (id == null || id.isBlank()) {
            long exists = qaRuleMapper.selectCount(
                    Wrappers.<QaRule>lambdaQuery()
                            .eq(QaRule::getTenantCode, tenant)
                            .eq(QaRule::getRuleName, ruleName.trim())
                            .eq(QaRule::getDeleted, false));
            if (exists > 0) {
                throw new BizException(40002, "已存在同名质检规则，请修改名称后重试");
            }
            QaRule created = QaRule.builder()
                    .id(idGenerator.nextId())
                    .tenantCode(tenant)
                    .ruleName(ruleName.trim())
                    .ruleType(ruleType)
                    .ruleContent(ruleContent)
                    .weight(weight)
                    .isEnabled(enabled)
                    .isRealtime(realtime)
                    .hitKeywords(hitKeywords)
                    .severity(severity == null ? 2 : severity)
                    .timeoutSeconds(timeoutSeconds == null ? 60 : timeoutSeconds)
                    .creator(String.valueOf(user.userId()))
                    .deleted(false)
                    .build();
            try {
                qaRuleMapper.insert(created);
                rule = created;
            } catch (DuplicateKeyException e) {
                throw new BizException(40002, "已存在同名质检规则，请修改名称后重试");
            }
        } else {
            rule = qaRuleMapper.selectById(Long.parseLong(id));
            if (rule == null || !tenant.equals(rule.getTenantCode())
                    || Boolean.TRUE.equals(rule.getDeleted())) {
                throw new BizException(40401, "规则不存在");
            }
            rule.setRuleName(ruleName.trim());
            rule.setRuleType(ruleType);
            rule.setRuleContent(ruleContent);
            rule.setWeight(weight);
            rule.setIsEnabled(enabled);
            rule.setIsRealtime(realtime);
            rule.setHitKeywords(hitKeywords);
            rule.setSeverity(severity == null ? 2 : severity);
            rule.setTimeoutSeconds(timeoutSeconds == null ? 60 : timeoutSeconds);
            rule.setEditor(String.valueOf(user.userId()));
            rule.setUpdateTime(now);
            qaRuleMapper.updateById(rule);
        }
        return toRuleVO(rule);
    }

    /**
     * 删除规则（逻辑删除）。
     */
    @Transactional
    public void deleteRule(LoginUser user, String id) {
        String tenant = tenantOf(user);
        QaRule rule = qaRuleMapper.selectById(Long.parseLong(id));
        if (rule == null || !tenant.equals(rule.getTenantCode())
                || Boolean.TRUE.equals(rule.getDeleted())) {
            throw new BizException(40401, "规则不存在");
        }
        rule.setDeleted(true);
        rule.setEditor(String.valueOf(user.userId()));
        rule.setUpdateTime(LocalDateTime.now());
        qaRuleMapper.updateById(rule);
    }

    /**
     * 首次进入只补默认规则，不再塞样例任务。
     *
     * <p>质检中心现在展示的是真实会话：会话一结束自动建任务，历史漏掉的用"发起全量质检"补扫。
     * 塞样例数据只会让人分不清哪些是真的。</p>
     */
    private void ensureDemoData(String tenant, long userId) {
        ensureRules(tenant, userId);
    }

    private void ensureRules(String tenant, long userId) {
        long count = qaRuleMapper.selectCount(
                Wrappers.<QaRule>lambdaQuery()
                        .eq(QaRule::getTenantCode, tenant)
                        .eq(QaRule::getDeleted, false));
        if (count > 0) {
            return;
        }
        for (RuleSeed seed : DEFAULT_RULES) {
            qaRuleMapper.insertIgnore(QaRule.builder()
                    .id(idGenerator.nextId())
                    .tenantCode(tenant)
                    .ruleName(seed.name())
                    .ruleType(seed.type())
                    .ruleContent(seed.content())
                    .weight(25)
                    .isEnabled(true)
                    .creator(String.valueOf(userId))
                    .deleted(false)
                    .build());
        }
    }

    private String generateTaskNo(String tenant) {
        for (int i = 0; i < 10; i++) {
            String no = "QC" + TASK_TIME.format(LocalDateTime.now()) + randomCode(3);
            long count = qaTaskMapper.selectCount(
                    Wrappers.<QaTask>lambdaQuery()
                            .eq(QaTask::getTenantCode, tenant)
                            .eq(QaTask::getTaskNo, no));
            if (count == 0) {
                return no;
            }
        }
        throw new BizException(50001, "质检任务编号生成失败");
    }

    private QaTask requireTask(String tenant, String taskNo) {
        QaTask task = qaTaskMapper.selectOne(
                Wrappers.<QaTask>lambdaQuery()
                        .eq(QaTask::getTenantCode, tenant)
                        .eq(QaTask::getTaskNo, taskNo)
                        .eq(QaTask::getDeleted, false)
                        .last("LIMIT 1"));
        if (task == null) {
            throw new BizException(40401, "质检任务不存在");
        }
        return task;
    }

    private TaskVO toTaskVO(QaTask task) {
        AiResult ai = parseAi(task);
        return new TaskVO(
                String.valueOf(task.getId()),
                task.getTaskNo(),
                ai.sessionName(),
                ai.agentName(),
                number(task.getAiScore()),
                number(task.getReviewScore()),
                task.getRiskLevel(),
                riskText(task.getRiskLevel()),
                task.getStatus(),
                statusText(task.getStatus()),
                task.getCreateTime() == null ? null : task.getCreateTime().toString().replace('T', ' '),
                task.getReviewTime() == null ? null : task.getReviewTime().toString().replace('T', ' '),
                ai.comment(),
                ai.rules(),
                ai.alerts().size(),
                severeCount(ai)
        );
    }

    private RuleVO toRuleVO(QaRule rule) {
        return new RuleVO(
                String.valueOf(rule.getId()),
                rule.getRuleName(),
                rule.getRuleType(),
                typeText(rule.getRuleType()),
                rule.getRuleContent(),
                rule.getWeight(),
                Boolean.TRUE.equals(rule.getIsEnabled()),
                !Boolean.FALSE.equals(rule.getIsRealtime()),
                rule.getHitKeywords(),
                rule.getSeverity() == null ? 2 : rule.getSeverity(),
                rule.getTimeoutSeconds() == null ? 60 : rule.getTimeoutSeconds(),
                rule.getUpdateTime() == null ? null : rule.getUpdateTime().toString().replace('T', ' ')
        );
    }

    private List<QaRule> enabledRules(String tenant) {
        return qaRuleMapper.selectList(
                Wrappers.<QaRule>lambdaQuery()
                        .eq(QaRule::getTenantCode, tenant)
                        .eq(QaRule::getDeleted, false)
                        .eq(QaRule::getIsEnabled, true)
                        .orderByAsc(QaRule::getRuleType));
    }

    private AiResult parseAi(QaTask task) {
        if (task.getAiResult() == null || task.getAiResult().isBlank()) {
            return new AiResult("会话-" + task.getId(), "客服", "AI 未返回明细", List.of(), "", List.of());
        }
        try {
            Map<String, Object> map = JSON.readValue(task.getAiResult(),
                    new TypeReference<Map<String, Object>>() {
                    });
            @SuppressWarnings("unchecked")
            List<String> rules = (List<String>) map.getOrDefault("rules", List.of());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> alerts = (List<Map<String, Object>>) map.getOrDefault("alerts", List.of());
            return new AiResult(
                    string(map.get("sessionName"), "会话"),
                    string(map.get("agentName"), "客服"),
                    string(map.get("comment"), ""),
                    rules == null ? List.of() : rules,
                    string(map.get("dialog"), ""),
                    alerts == null ? List.of() : alerts
            );
        } catch (Exception e) {
            return new AiResult("会话-" + task.getId(), "客服", "AI 结果解析失败", List.of(), "", List.of());
        }
    }

    private boolean matches(QaTask task, String kw) {
        AiResult ai = parseAi(task);
        String hay = (task.getTaskNo() + " " + ai.sessionName() + " " + ai.agentName()).toLowerCase();
        return hay.contains(kw);
    }

    private String writeJson(Map<String, Object> map) {
        try {
            String json = JSON.writeValueAsString(map);
            return json.length() > 500 ? json.substring(0, 500) : json;
        } catch (Exception e) {
            return "{}";
        }
    }

    private int countStatus(List<QaTask> tasks, int status) {
        return (int) tasks.stream().filter(t -> t.getStatus() != null && t.getStatus() == status).count();
    }

    private int countRisk(List<QaTask> tasks, int risk) {
        return (int) tasks.stream().filter(t -> t.getRiskLevel() != null && t.getRiskLevel() == risk).count();
    }

    private double average(List<BigDecimal> values) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        return values.stream()
                .filter(v -> v != null)
                .mapToDouble(BigDecimal::doubleValue)
                .average().orElse(0);
    }

    private double round2(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private Double number(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    private String string(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private String typeText(int type) {
        return switch (type) {
            case 1 -> "敏感词";
            case 2 -> "承诺规范";
            case 3 -> "必答项";
            case 4 -> "情绪识别";
            case 5 -> "响应超时";
            default -> "其他";
        };
    }

    private String riskText(int risk) {
        return switch (risk) {
            case 1 -> "低风险";
            case 2 -> "中风险";
            case 3 -> "高风险";
            default -> "未知";
        };
    }

    private String statusText(int status) {
        return switch (status) {
            case 1 -> "待复核";
            case 2 -> "已通过";
            case 3 -> "已驳回";
            default -> "未知";
        };
    }

    private String tenantOf(LoginUser user) {
        if (user == null || user.userType() != 2) {
            throw new BizException(40301, "仅企业成员可使用质检中心");
        }
        String tenant = user.tenantCode();
        if (tenant == null || tenant.isBlank() || "PLATFORM".equals(tenant)) {
            throw new BizException(40301, "请先完成企业开通后再使用质检中心");
        }
        return tenant;
    }

    private String randomCode(int length) {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(RANDOM.nextInt(chars.length())));
        }
        return sb.toString();
    }

    private record RuleSeed(String name, int type, String typeText, String content) {
    }

    private record SampleSpec(
            String sessionName,
            String agentName,
            double aiScore,
            int riskLevel,
            List<String> ruleNames,
            String comment,
            String dialog
    ) {
    }

    private record AiResult(String sessionName, String agentName, String comment, List<String> rules, String dialog,
                            List<Map<String, Object>> alerts) {
    }

    private static final SampleSpec[] SAMPLE_POOL = {
            new SampleSpec("C-2077 · 赵一诺", "陈思远", 92, 1, List.of(),
                    "整体服务专业，应答准确，建议保持并主动邀评。",
                    "客户：我买的东西什么时候能到？\n"
                            + "客服：您好，我看到您的订单 A20260911001 已从杭州仓发出，预计明天下午 3 点前送达，物流单号 SF1234567890，您可以在订单详情里实时查看。\n"
                            + "客户：好的，谢谢。\n"
                            + "客服：不客气，后续有任何问题随时找我。"),
            new SampleSpec("C-2078 · 王浩宇", "郑凯文", 78, 2, List.of("服务承诺规范"),
                    "承诺较含糊，未给出明确时间节点，建议补充可兑现时限。",
                    "客户：我上周申请的退款还没到账。\n"
                            + "客服：不好意思，我帮您催一下，尽快处理。\n"
                            + "客户：大概什么时候能到？\n"
                            + "客服：应该很快的，您放心。"),
            new SampleSpec("C-2079 · 苏明轩", "吴佳怡", 68, 3, List.of("敏感词与禁语", "情绪安抚"),
                    "出现不耐烦表述，客户不满时未先安抚，需重点改进。",
                    "客户：你们这什么服务，等了一个小时都没人理我！\n"
                            + "客服：这个问题不是我们这边的问题，您自己看看是不是地址填错了，我这边也管不了。\n"
                            + "客户：你什么态度？\n"
                            + "客服：我态度就这样，您要投诉就投诉吧。"),
            new SampleSpec("C-2080 · 张梦琪", "林晓彤", 96, 1, List.of(),
                    "话术符合最新政策，情绪管理与升级机制均规范。",
                    "客户：我想问一下发票怎么开。\n"
                            + "客服：您好，开票需要提供公司名称和税号，请问是开公司抬头还是个人抬头？\n"
                            + "客户：公司的。\n"
                            + "客服：好的，请把公司名称和税号发给我，我提交后 24 小时内会发送到您的邮箱。"),
            new SampleSpec("C-2081 · 罗晓峰", "周子航", 84, 2, List.of("必答项完整"),
                    "未主动核实订单号，已补充确认，建议强化关键信息核验习惯。",
                    "客户：我的订单一直没收到。\n"
                            + "客服：好的，我帮您看看物流。\n"
                            + "客户：你都不问我订单号吗？\n"
                            + "客服：抱歉，麻烦提供一下订单号，我马上为您核实。"),
            new SampleSpec("C-2082 · 陈博文", "孙雨桐", 88, 1, List.of("服务承诺规范"),
                    "承诺了补偿但未明确到账时间，需按规范补充时限。",
                    "客户：收到的商品有质量问题，怎么处理？\n"
                            + "客服：非常抱歉给您带来不好的体验，可以为您办理退货，同时补偿 20 元优惠券。\n"
                            + "客户：优惠券什么时候到账？\n"
                            + "客服：这个后面会安排的，您放心。"),
            new SampleSpec("C-2083 · 刘雅静", "郑凯文", 90, 1, List.of(),
                    "处理路径清晰，主动同步进度，服务体验良好。",
                    "客户：我的优惠券怎么用不了？\n"
                            + "客服：您好，这张券满 199 元可用，您当前订单是 168 元，还差 31 元。需要我帮您看看有没有合适的凑单品吗？\n"
                            + "客户：好的，麻烦你了。\n"
                            + "客服：已经为您推荐两款常用商品，加入购物车后即可使用优惠券。"),
            new SampleSpec("C-2084 · 马天宇", "陈思远", 75, 2, List.of("情绪安抚", "必答项完整"),
                    "客户情绪激动时回应偏机械，需加强共情话术。",
                    "客户：我要投诉！快递员把包裹直接扔在门口就走了！\n"
                            + "客服：您好，请提供一下订单号，我这边核实。\n"
                            + "客户：我现在很生气，你们就是这么送快递的吗？\n"
                            + "客服：嗯，那您先给我订单号。"),
            new SampleSpec("C-2085 · 高雯", "吴佳怡", 81, 2, List.of("敏感词与禁语"),
                    "个别表述口语化较随意，建议使用标准化话术。",
                    "客户：这个价格还能再便宜一点吗？\n"
                            + "客服：这个价已经很实惠了，别家您随便看看，都差不多这个价。\n"
                            + "客户：好吧。\n"
                            + "客服：嗯，那您考虑下。"),
            new SampleSpec("C-2086 · 沈倩", "周子航", 71, 3, List.of("服务承诺规范", "情绪安抚"),
                    "两次承诺时间不一致，且未安抚客户，需重点复盘。",
                    "客户：我的退款到底什么时候能到？\n"
                            + "客服：您别急，这两天会给您处理的，差不多一周左右吧。\n"
                            + "客户：上周你们就说到账了！\n"
                            + "客服：那是系统那边的问题，您再等等。"),
    };
}
