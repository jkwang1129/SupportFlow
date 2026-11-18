package io.supportflow.tenant.mapper;

import io.supportflow.tenant.entity.Tenant;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * tenant 租户表 Mapper。
 */
@Mapper
public interface TenantMapper extends BaseMapper<Tenant> {

    /**
     * 查询指定日期前缀下最新租户编码（含逻辑删除，避免编码复用冲突）。
     */
    String findLatestCodeByDatePrefix(@Param("datePrefix") String datePrefix);
}
