package io.supportflow.ops.mapper;

import io.supportflow.ops.entity.CustomerSuccessTask;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * customer_success_task 回访任务 Mapper（标准 CRUD）。
 */
@Mapper
public interface CustomerSuccessTaskMapper extends BaseMapper<CustomerSuccessTask> {
}
