package io.supportflow.tenant.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 安全事件（tenant_security_event）读写：列表 / 概览 / 趋势 / 处置。
 *
 * <p>事件由探测侧（网关与数据面）追加，平台侧只能处置、不能改事件本身——
 * "探测到的事实"不该被运营改掉。</p>
 */
@Mapper
public interface SecurityEventMapper {

    List<Map<String, Object>> selectEvents(@Param("eventType") Integer eventType,
                                           @Param("handleStatus") Integer handleStatus,
                                           @Param("tenantCode") String tenantCode,
                                           @Param("limit") int limit,
                                           @Param("offset") long offset);

    long countEvents(@Param("eventType") Integer eventType,
                     @Param("handleStatus") Integer handleStatus,
                     @Param("tenantCode") String tenantCode);

    Map<String, Object> selectOverview();

    List<Map<String, Object>> selectDaily(@Param("days") int days);

    int handle(@Param("id") long id,
               @Param("status") int status,
               @Param("remark") String remark,
               @Param("handler") String handler);
}
