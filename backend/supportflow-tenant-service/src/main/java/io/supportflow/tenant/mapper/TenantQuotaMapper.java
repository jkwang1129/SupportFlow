package io.supportflow.tenant.mapper;

import io.supportflow.tenant.entity.TenantQuota;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 租户配额 Mapper（标准 CRUD）。 */
@Mapper
public interface TenantQuotaMapper extends BaseMapper<TenantQuota> {
}
