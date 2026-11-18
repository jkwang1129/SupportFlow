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
@TableName("channel")
public class Channel {
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;
    private String tenantCode;
    private String channelId;
    /** 1-网站 2-公众号 3-小程序 4-App 5-400热线 6-邮件 7-自定义 */
    private Integer channelType;
    private String name;
    @TableField("\"desc\"")
    private String desc;
    private Long skillGroupId;
    /** 1-启用 2-停用 */
    private Integer status;
    /** 1-未配置 2-待上线 3-运行中 */
    private Integer stage;
    private Boolean isEnabled;
    /** 访客接入域名白名单（逗号分隔，支持 *.example.com；为空表示不限制来源） */
    private String allowedOrigins;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private String creator;
    private String editor;
    @TableField("is_deleted")
    private Boolean deleted;
}
