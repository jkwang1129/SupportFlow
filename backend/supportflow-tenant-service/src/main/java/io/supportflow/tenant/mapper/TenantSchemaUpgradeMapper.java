package io.supportflow.tenant.mapper;

import io.supportflow.tenant.entity.TenantSchemaUpgrade;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 租户库升级记录 Mapper（标准 CRUD）。 */
@Mapper
public interface TenantSchemaUpgradeMapper extends BaseMapper<TenantSchemaUpgrade> {
}
