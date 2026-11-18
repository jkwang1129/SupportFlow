package io.supportflow.billing.mapper;

import io.supportflow.billing.entity.BillingUsageDaily;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 按天用量 Mapper（标准 CRUD；累加走上报 SQL）。 */
@Mapper
public interface BillingUsageDailyMapper extends BaseMapper<BillingUsageDaily> {
}
