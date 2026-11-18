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

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("skill_group")
public class SkillGroup {
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;
    private String tenantCode;
    private String name;
    private String description;
    private Boolean isDefault;
    private Boolean isEnabled;
    /** 排队超过这个秒数就升级：放宽技能组限制（0 表示不升级） */
    private Integer overflowAfterSeconds;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private String creator;
    private String editor;
    @TableField("is_deleted")
    private Boolean deleted;
}
