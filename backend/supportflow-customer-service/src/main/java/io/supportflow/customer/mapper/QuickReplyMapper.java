package io.supportflow.customer.mapper;

import io.supportflow.customer.entity.QuickReply;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 快捷话术 Mapper：CRUD 用 MyBatis-Plus，使用次数用一条原子自增 SQL。
 *
 * <p>为什么不用「查出来 +1 再写回」：坐席可能同时点同一条话术，
 * 读改写会把并发的那几次丢掉；`use_count = use_count + 1` 由数据库保证不丢。</p>
 */
@Mapper
public interface QuickReplyMapper extends BaseMapper<QuickReply> {

    @Update("UPDATE quick_reply SET use_count = use_count + 1, update_time = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND tenant_code = #{tenantCode} AND is_deleted = FALSE")
    int bumpUseCount(@Param("tenantCode") String tenantCode, @Param("id") long id);
}
