package io.supportflow.tenant.mapper;

import io.supportflow.tenant.entity.Enterprise;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * enterprise 企业表 Mapper。
 */
@Mapper
public interface EnterpriseMapper extends BaseMapper<Enterprise> {

    /**
     * 按申请人用户 ID 查询企业（注册草稿 creator 存申请人用户 ID），SQL 见 EnterpriseMapper.xml。
     */
    Enterprise findByApplicantId(@Param("applicantId") String applicantId);

    /**
     * 按租户编码查询企业（企业开通后，租户里的每个成员都该看到这条企业资料）。
     */
    Enterprise findByTenantCode(@Param("tenantCode") String tenantCode);

    /**
     * 查询指定日期前缀下最新企业编码（含逻辑删除，避免编码复用冲突）。
     */
    String findLatestCodeByDatePrefix(@Param("datePrefix") String datePrefix);
}
