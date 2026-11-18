package io.supportflow.billing.mapper;

import io.supportflow.billing.entity.PaymentConfig;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * payment_config 支付渠道配置 Mapper（标准 CRUD；一个渠道一行）。
 */
@Mapper
public interface PaymentConfigMapper extends BaseMapper<PaymentConfig> {
}
