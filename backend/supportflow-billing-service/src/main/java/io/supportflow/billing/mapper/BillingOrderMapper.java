package io.supportflow.billing.mapper;

import io.supportflow.billing.entity.BillingOrder;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 订单 Mapper（标准 CRUD）。 */
@Mapper
public interface BillingOrderMapper extends BaseMapper<BillingOrder> {
}
