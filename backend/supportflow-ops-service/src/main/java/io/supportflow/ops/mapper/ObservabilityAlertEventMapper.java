package io.supportflow.ops.mapper;

import io.supportflow.ops.entity.ObservabilityAlertEvent;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * observability_alert_event 告警事件 Mapper。
 *
 * <p>列表与概览都走 XML：事件列表要按状态/级别/租户筛，概览要用
 * {@code COUNT(1) FILTER} 一次查完（五次 count 查询在告警页是白花的钱）。</p>
 */
@Mapper
public interface ObservabilityAlertEventMapper extends BaseMapper<ObservabilityAlertEvent> {

    List<Map<String, Object>> selectEvents(@Param("handleStatus") Integer handleStatus,
                                           @Param("level") Integer level,
                                           @Param("tenantCode") String tenantCode,
                                           @Param("limit") int limit,
                                           @Param("offset") long offset);

    long countEvents(@Param("handleStatus") Integer handleStatus,
                     @Param("level") Integer level,
                     @Param("tenantCode") String tenantCode);

    Map<String, Object> selectOverview();

    /** 处置：事件内容不可改，只允许动这四列 */
    int handle(@Param("id") long id,
               @Param("status") int status,
               @Param("remark") String remark,
               @Param("handler") String handler);

    /** 标记已通知企业（避免同一事件重复打扰） */
    int markNotified(@Param("id") long id);

    /**
     * 落一条事件，重复就跳过（返回 1 = 新建、0 = 已存在）。
     *
     * <p>为什么不用「先查再插 + catch 重复键」：PostgreSQL 一旦有一条语句违反约束，
     * **整个事务就进入 aborted 状态**，后面任何语句都会报
     * "current transaction is aborted"，catch 住的异常救不回来——
     * 定时评估与手动评估并发时就会踩到（自检里真实复现过一次）。
     * 交给 `ON CONFLICT DO NOTHING` 判断，既没有异常也没有脏事务。</p>
     */
    int insertIgnore(@Param("event") ObservabilityAlertEvent event);
}
