package io.supportflow.billing.mapper;

import io.supportflow.billing.entity.BillingStatement;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 账期账单 Mapper（标准 CRUD）。 */
@Mapper
public interface BillingStatementMapper extends BaseMapper<BillingStatement> {
}
