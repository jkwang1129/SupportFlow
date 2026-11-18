package io.supportflow.ops.mapper;

import io.supportflow.ops.entity.Announcement;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * announcement 平台公告 Mapper，SQL 见 AnnouncementMapper.xml。
 */
@Mapper
public interface AnnouncementMapper extends BaseMapper<Announcement> {

    /** 公告列表总数（按状态 / 类型 / 关键字筛选） */
    long countAnnouncements(@Param("status") Integer status,
                            @Param("announceType") Integer announceType,
                            @Param("keyword") String keyword);

    /** 公告分页列表 */
    List<Map<String, Object>> selectAnnouncements(@Param("status") Integer status,
                                                  @Param("announceType") Integer announceType,
                                                  @Param("keyword") String keyword,
                                                  @Param("limit") int limit,
                                                  @Param("offset") long offset);
}
