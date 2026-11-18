package io.supportflow.user.mapper;

import io.supportflow.user.dto.PlatformUserRow;
import io.supportflow.user.entity.SysUser;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * sys_user 用户表 Mapper。
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    /**
     * 按登录账号查询（手机号 / 邮箱 / 用户编号任一匹配），SQL 见 SysUserMapper.xml。
     */
    SysUser findByAccount(@Param("account") String account);

    /**
     * 判断手机号或邮箱是否已被注册。
     */
    int countByPhoneOrEmail(@Param("phone") String phone, @Param("email") String email);

    /**
     * 企业成员列表（含角色名称），SQL 见 SysUserMapper.xml。
     */
    List<Map<String, Object>> selectEnterpriseMembers(@Param("tenantCode") String tenantCode);

    // ------------------------------------------------------------------ 平台账号中心
    //
    // 平台侧「用户管理」要跨租户看所有账号（平台账号 + 企业账号），
    // 并带出角色名。分页不走 MyBatis-Plus 的 IPage：那条路线会自动生成 count SQL，
    // 而这里带了 LEFT JOIN LATERAL，交给拦截器改写容易出意外——手写 count 更稳。

    /** 平台账号中心：账号总数 */
    long countPlatformUsers(@Param("userType") Integer userType,
                            @Param("status") Integer status,
                            @Param("tenantCode") String tenantCode,
                            @Param("keyword") String keyword);

    /** 平台账号中心：分页列表（含角色名 / 角色编码 / 最近登录） */
    List<Map<String, Object>> selectPlatformUsers(@Param("userType") Integer userType,
                                                  @Param("status") Integer status,
                                                  @Param("tenantCode") String tenantCode,
                                                  @Param("keyword") String keyword,
                                                  @Param("limit") int limit,
                                                  @Param("offset") long offset);

    /** 平台账号中心：导出用（同样的筛选条件，不分页） */
    List<Map<String, Object>> selectPlatformUsersForExport(@Param("userType") Integer userType,
                                                           @Param("status") Integer status,
                                                           @Param("tenantCode") String tenantCode,
                                                           @Param("keyword") String keyword);
}
