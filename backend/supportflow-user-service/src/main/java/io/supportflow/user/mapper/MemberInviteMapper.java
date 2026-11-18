package io.supportflow.user.mapper;

import io.supportflow.user.entity.MemberInvite;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * member_invite Mapper。
 */
@Mapper
public interface MemberInviteMapper extends BaseMapper<MemberInvite> {

    /**
     * 邀请列表（关联邀请人姓名与角色名称），SQL 见 MemberInviteMapper.xml。
     */
    List<Map<String, Object>> selectInviteList(@Param("tenantCode") String tenantCode);
}
