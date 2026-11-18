package io.supportflow.customer.mapper;

import io.supportflow.customer.entity.SkillGroupMember;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * skill_group_member 技能组坐席绑定 Mapper。
 */
@Mapper
public interface SkillGroupMemberMapper extends BaseMapper<SkillGroupMember> {
}
