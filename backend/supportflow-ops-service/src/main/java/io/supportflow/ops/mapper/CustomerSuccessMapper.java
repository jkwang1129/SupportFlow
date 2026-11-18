package io.supportflow.ops.mapper;

import io.supportflow.ops.entity.CustomerSuccess;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * customer_success 客户成功随访状态 Mapper（一租户一行，标准 CRUD）。
 */
@Mapper
public interface CustomerSuccessMapper extends BaseMapper<CustomerSuccess> {
}
