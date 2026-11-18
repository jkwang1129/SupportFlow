package io.supportflow.tenant.mapper;

import io.supportflow.tenant.entity.TenantSecurityEvent;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 租户安全事件 Mapper（标准 CRUD）。 */
@Mapper
public interface TenantSecurityEventMapper extends BaseMapper<TenantSecurityEvent> {
}
