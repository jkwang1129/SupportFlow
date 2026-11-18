package io.supportflow.ops.mapper;

import io.supportflow.ops.entity.SecurityPolicy;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** security_policy 平台安全策略 Mapper（全平台一行）。 */
@Mapper
public interface SecurityPolicyMapper extends BaseMapper<SecurityPolicy> {
}
