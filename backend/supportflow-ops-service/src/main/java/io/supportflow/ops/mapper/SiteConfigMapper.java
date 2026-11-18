package io.supportflow.ops.mapper;

import io.supportflow.ops.entity.SiteConfig;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * site_config 官网配置 Mapper（按区块 + 版本号存，标准 CRUD 就够）。
 */
@Mapper
public interface SiteConfigMapper extends BaseMapper<SiteConfig> {
}
