package io.supportflow.customer.mapper;

import io.supportflow.customer.entity.Customer;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * customer 客户 Mapper（标准 CRUD；客户 360 的聚合查询都在 SessionMapper 里，
 * 订单相关的自定义 SQL 随"订单功能下线"一起移除了）。
 */
@Mapper
public interface CustomerMapper extends BaseMapper<Customer> {
}
