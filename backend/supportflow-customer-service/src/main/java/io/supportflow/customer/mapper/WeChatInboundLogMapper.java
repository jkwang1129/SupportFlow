package io.supportflow.customer.mapper;

import io.supportflow.customer.entity.WeChatInboundLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WeChatInboundLogMapper extends BaseMapper<WeChatInboundLog> {
}
