package io.supportflow.customer.mapper;

import io.supportflow.customer.entity.FileMeta;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * file_meta 文件元数据 Mapper。
 */
@Mapper
public interface FileMetaMapper extends BaseMapper<FileMeta> {

    /**
     * 该租户已占用存储（字节）。计费用的存储量口径，SQL 见 FileMetaMapper.xml。
     */
    long sumSizeByTenant(@Param("tenantCode") String tenantCode);
}
