package io.supportflow.tenant.mapper;

import io.supportflow.tenant.entity.TenantUsageDaily;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 租户日用量 Mapper（标准 CRUD；累加走上报 SQL）。 */
@Mapper
public interface TenantUsageDailyMapper extends BaseMapper<TenantUsageDaily> {
}
