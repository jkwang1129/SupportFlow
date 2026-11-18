package io.supportflow.customer.mapper;

import io.supportflow.customer.entity.ChannelCallRecord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 400 热线通话记录 Mapper，SQL 见 ChannelCallRecordMapper.xml。
 */
@Mapper
public interface ChannelCallRecordMapper extends BaseMapper<ChannelCallRecord> {

    /** 通话列表（按状态 / 号码筛选 + 分页） */
    List<Map<String, Object>> selectCallPage(@Param("tenantCode") String tenantCode,
                                             @Param("channelId") Long channelId,
                                             @Param("status") Integer status,
                                             @Param("keyword") String keyword,
                                             @Param("limit") int limit,
                                             @Param("offset") long offset);

    /** 列表总数（与列表共用过滤条件） */
    long countCallPage(@Param("tenantCode") String tenantCode,
                       @Param("channelId") Long channelId,
                       @Param("status") Integer status,
                       @Param("keyword") String keyword);

    /** 电话渠道的今日概况：呼入 / 接通 / 转人工 / 未接 / 平均时长 */
    Map<String, Object> selectCallSummary(@Param("tenantCode") String tenantCode,
                                          @Param("channelId") Long channelId);
}
