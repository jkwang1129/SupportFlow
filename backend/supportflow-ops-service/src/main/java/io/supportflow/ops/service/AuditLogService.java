package io.supportflow.ops.service;

import io.supportflow.common.audit.AuditLogRecord;
import io.supportflow.common.exception.BizException;
import io.supportflow.common.id.SnowflakeIdGenerator;
import io.supportflow.common.util.Texts;
import io.supportflow.ops.dto.AuditLogPageVO;
import io.supportflow.ops.dto.AuditLogRowVO;
import io.supportflow.ops.dto.AuditModuleVO;
import io.supportflow.ops.entity.AuditLog;
import io.supportflow.ops.mapper.AuditLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 审计日志：写入（内部接口调用）+ 查询 / 导出（平台页）。
 *
 * <p>表是**只追加**的：这里没有 update、没有 delete。写进去的日志就是历史事实，
 * 后面改了账号名、删了角色都不影响已有日志。</p>
 */
@Service
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    /** 表里 detail 是 VARCHAR(1024)，这里留点余量 */
    private static final int DETAIL_MAX = 1000;
    private static final int SHORT_MAX = 64;

    private final AuditLogMapper auditLogMapper;
    private final SnowflakeIdGenerator idGenerator;

    public AuditLogService(AuditLogMapper auditLogMapper, SnowflakeIdGenerator idGenerator) {
        this.auditLogMapper = auditLogMapper;
        this.idGenerator = idGenerator;
    }

    /**
     * 记一条审计日志（由各服务的 AuditLogClient 通过内部接口调进来）。
     *
     * <p>audit_time 取**服务端时间**：各服务自己传时间的话，多台机器时钟不一致会串序。</p>
     */
    @Transactional
    public void record(AuditLogRecord record) {
        if (record == null || isBlank(record.module()) || isBlank(record.action())) {
            throw new BizException(40001, "审计日志缺少 module / action");
        }
        LocalDateTime now = LocalDateTime.now();
        auditLogMapper.insert(AuditLog.builder()
                .id(idGenerator.nextId())
                .tenantCode(isBlank(record.tenantCode()) ? "PLATFORM" : record.tenantCode())
                .operatorId(record.operatorId())
                .operatorName(shorten(record.operatorName(), SHORT_MAX))
                .module(shorten(record.module(), 32))
                .action(shorten(record.action(), SHORT_MAX))
                .targetType(shorten(record.targetType(), SHORT_MAX))
                .targetId(shorten(record.targetId(), SHORT_MAX))
                .result(record.result() == null ? 1 : record.result())
                .detail(shorten(record.detail(), DETAIL_MAX))
                .ip(shorten(record.ip(), SHORT_MAX))
                .requestId(shorten(record.requestId(), SHORT_MAX))
                .auditTime(now)
                .createTime(now)
                .build());
    }

    /** 日志分页（按模块 / 结果 / 操作人 / 关键字 / 时间范围筛选） */
    public AuditLogPageVO page(String module, Integer result, String operator, String keyword,
                               String from, String to, int page, int pageSize) {
        String moduleFilter = Texts.blankToNull(module);
        String operatorFilter = Texts.blankToNull(operator);
        String keywordFilter = Texts.blankToNull(keyword);
        String fromFilter = Texts.blankToNull(from);
        String toFilter = Texts.blankToNull(to);

        long total = auditLogMapper.countLogs(moduleFilter, result, operatorFilter, keywordFilter,
                fromFilter, toFilter);
        List<Map<String, Object>> rows = auditLogMapper.selectLogs(moduleFilter, result, operatorFilter,
                keywordFilter, fromFilter, toFilter, pageSize, (long) (page - 1) * pageSize);
        List<AuditLogRowVO> list = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            list.add(new AuditLogRowVO(stringOf(row.get("id")), stringOf(row.get("tenantCode")),
                    stringOf(row.get("operatorId")), stringOf(row.get("operatorName")),
                    stringOf(row.get("module")), stringOf(row.get("action")),
                    stringOf(row.get("targetType")), stringOf(row.get("targetId")),
                    intOrNull(row.get("result")), stringOf(row.get("detail")),
                    stringOf(row.get("ip")), stringOf(row.get("requestId")),
                    localDateTimeOrNull(row.get("auditTime"))));
        }
        return new AuditLogPageVO(total, page, pageSize, list);
    }

    /** 有日志的模块清单（筛选下拉用） */
    public List<AuditModuleVO> modules() {
        List<AuditModuleVO> list = new ArrayList<>();
        for (Map<String, Object> row : auditLogMapper.selectModuleOptions()) {
            list.add(new AuditModuleVO(stringOf(row.get("module")), longOrNull(row.get("cnt"))));
        }
        return list;
    }

    private static String stringOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Integer intOrNull(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static Long longOrNull(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    /** JDBC 的 TIMESTAMP 可能是 java.sql.Timestamp / LocalDateTime / 字符串，统一成 LocalDateTime */
    private static LocalDateTime localDateTimeOrNull(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime time) {
            return time;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        return LocalDateTime.parse(String.valueOf(value).replace(' ', 'T'));
    }

    /** 导出 CSV（带 UTF-8 BOM，Excel 打开不乱码） */
    public String exportCsv(String module, Integer result, String operator, String keyword,
                            String from, String to) {
        List<Map<String, Object>> rows = auditLogMapper.selectLogsForExport(
                Texts.blankToNull(module), result, Texts.blankToNull(operator), Texts.blankToNull(keyword),
                Texts.blankToNull(from), Texts.blankToNull(to));
        StringBuilder sb = new StringBuilder("\uFEFF");
        sb.append("时间,操作人,模块,操作,对象类型,对象标识,结果,详情,IP\n");
        for (Map<String, Object> row : rows) {
            sb.append(Texts.csv(row.get("auditTime"))).append(',')
                    .append(Texts.csv(row.get("operatorName"))).append(',')
                    .append(Texts.csv(row.get("module"))).append(',')
                    .append(Texts.csv(row.get("action"))).append(',')
                    .append(Texts.csv(row.get("targetType"))).append(',')
                    .append(Texts.csv(row.get("targetId"))).append(',')
                    .append(Texts.csv(resultText(row.get("result")))).append(',')
                    .append(Texts.csv(row.get("detail"))).append(',')
                    .append(Texts.csv(row.get("ip"))).append('\n');
        }
        log.info("导出审计日志 {} 条（module={} result={}）", rows.size(), module, result);
        return sb.toString();
    }

    // ------------------------------------------------------------------ 内部

    private static String resultText(Object value) {
        if (value == null) {
            return "";
        }
        return ((Number) value).intValue() == 1 ? "成功" : "失败";
    }

    private static String shorten(String value, int max) {
        if (value == null) {
            return null;
        }
        String text = value.replaceAll("[\\r\\n]+", " ").trim();
        if (text.isEmpty()) {
            return null;
        }
        return text.length() > max ? text.substring(0, max - 1) + "…" : text;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

}
