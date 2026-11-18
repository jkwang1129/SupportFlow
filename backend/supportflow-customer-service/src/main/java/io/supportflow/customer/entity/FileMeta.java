package io.supportflow.customer.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * file_meta 文件元数据实体（customer_db）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("file_meta")
public class FileMeta {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String tenantCode;

    private String fileNo;

    private String fileName;

    private String objectKey;

    private Long fileSize;

    private String mimeType;

    /** 业务类型：1-头像、2-附件、3-导出、4-发票 */
    private Integer bizType;

    private Long bizId;

    private LocalDateTime createTime;

    private String creator;

    private LocalDateTime updateTime;

    private String editor;

    @TableField("is_deleted")
    private Boolean deleted;
}
