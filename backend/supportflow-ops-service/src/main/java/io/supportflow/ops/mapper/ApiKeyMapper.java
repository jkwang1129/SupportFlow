package io.supportflow.ops.mapper;

import io.supportflow.ops.entity.ApiKey;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** api_key 开放 API 密钥 Mapper（标准 CRUD）。 */
@Mapper
public interface ApiKeyMapper extends BaseMapper<ApiKey> {
}
