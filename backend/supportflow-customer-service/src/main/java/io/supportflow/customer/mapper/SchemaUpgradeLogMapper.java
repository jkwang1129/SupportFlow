package io.supportflow.customer.mapper;

import io.supportflow.customer.entity.SchemaUpgradeLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 租户库升级记录 Mapper（标准 CRUD，查询条件走 LambdaQuery）。
 */
@Mapper
public interface SchemaUpgradeLogMapper extends BaseMapper<SchemaUpgradeLog> {
}
