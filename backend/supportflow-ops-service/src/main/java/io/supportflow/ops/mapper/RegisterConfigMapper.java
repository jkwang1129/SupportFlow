package io.supportflow.ops.mapper;

import io.supportflow.ops.entity.RegisterConfig;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * register_config 注册审核策略 Mapper（全平台一条，标准 CRUD）。
 */
@Mapper
public interface RegisterConfigMapper extends BaseMapper<RegisterConfig> {
}
