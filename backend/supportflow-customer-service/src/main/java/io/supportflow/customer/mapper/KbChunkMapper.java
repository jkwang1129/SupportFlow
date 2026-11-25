package io.supportflow.customer.mapper;

import io.supportflow.customer.entity.KbChunk;
import io.supportflow.customer.dto.bot.KbIndexRollbackOption;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 知识切片读取与发布。切片由 AI 生成，Java 在文档事务内切换生效版本。
 */
@Mapper
public interface KbChunkMapper extends BaseMapper<KbChunk> {

    /**
     * 某个文档的切片（按序号正序）。
     *
     * <p>注意只查展示需要的列：向量列有 1024 个浮点数，取出来既慢又没人用。</p>
     */
    List<KbChunk> selectByDoc(
            @Param("tenantCode") String tenantCode,
            @Param("docId") Long docId,
            @Param("limit") int limit
    );

    /** 删除某个文档的全部生效切片。 */
    int deleteByDoc(@Param("tenantCode") String tenantCode, @Param("docId") Long docId);

    int deleteStagedByDoc(@Param("tenantCode") String tenantCode, @Param("docId") long docId);

    int activateAttempt(@Param("tenantCode") String tenantCode, @Param("docId") long docId,
                        @Param("attemptId") String attemptId);

    int savePrevious(@Param("tenantCode") String tenantCode, @Param("docId") long docId,
                     @Param("versionId") String versionId, @Param("content") String content);

    PreviousIndex findPrevious(@Param("tenantCode") String tenantCode, @Param("docId") long docId);

    KbIndexRollbackOption findRollbackOption(@Param("tenantCode") String tenantCode, @Param("docId") long docId);

    int restorePrevious(@Param("tenantCode") String tenantCode, @Param("docId") long docId);

    int deletePrevious(@Param("tenantCode") String tenantCode, @Param("docId") long docId);

    record PreviousIndex(String versionId, String contentSnapshot, int chunkCount) {}
}
