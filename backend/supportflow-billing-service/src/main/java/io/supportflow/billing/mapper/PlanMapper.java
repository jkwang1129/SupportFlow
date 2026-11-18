package io.supportflow.billing.mapper;

import io.supportflow.billing.entity.Plan;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 套餐 Mapper（标准 CRUD）。 */
@Mapper
public interface PlanMapper extends BaseMapper<Plan> {
}
