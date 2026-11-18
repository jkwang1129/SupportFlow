package io.supportflow.ops.mapper;

import io.supportflow.ops.entity.AuditLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * audit_log 审计日志 Mapper，SQL 见 AuditLogMapper.xml。
 *
 * <p>只提供"写入 + 查询"：审计日志不可改、不可删。</p>
 */
@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {

    /** 日志总数（按筛选条件） */
    long countLogs(@Param("module") String module,
                   @Param("result") Integer result,
                   @Param("operator") String operator,
                   @Param("keyword") String keyword,
                   @Param("from") String from,
                   @Param("to") String to);

    /**
     * 日志分页列表。
     *
     * <p>SQL 里直接用带引号的驼峰别名（{@code AS "auditTime"}），
     * 这样返回的 Map 就是前端要的字段名——不靠 Jackson 命名策略（踩过这个坑）。</p>
     */
    List<Map<String, Object>> selectLogs(@Param("module") String module,
                                         @Param("result") Integer result,
                                         @Param("operator") String operator,
                                         @Param("keyword") String keyword,
                                         @Param("from") String from,
                                         @Param("to") String to,
                                         @Param("limit") int limit,
                                         @Param("offset") long offset);

    /** 导出用（同样的筛选条件，不分页） */
    List<Map<String, Object>> selectLogsForExport(@Param("module") String module,
                                                 @Param("result") Integer result,
                                                 @Param("operator") String operator,
                                                 @Param("keyword") String keyword,
                                                 @Param("from") String from,
                                                 @Param("to") String to);

    /** 有日志的模块清单（给筛选下拉用，避免手写一份会过期的常量表） */
    List<Map<String, Object>> selectModuleOptions();
}
