package io.supportflow.tenant.mapper;

import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Map;

/**
 * 租户 AI 配额视图：配额 + 租户状态 + 企业名一次查出来（用量由 AI 服务提供）。
 */
@Mapper
public interface AiQuotaMapper {

    List<Map<String, Object>> selectQuotaView();
}
