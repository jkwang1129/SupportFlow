package io.supportflow.ops.mapper;

import io.supportflow.ops.entity.DataExport;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** data_export 导出审批 Mapper（标准 CRUD）。 */
@Mapper
public interface DataExportMapper extends BaseMapper<DataExport> {
}
