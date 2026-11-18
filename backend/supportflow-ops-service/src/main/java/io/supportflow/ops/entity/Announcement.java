package io.supportflow.ops.entity;

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
 * announcement 平台公告。
 *
 * <p>状态机：1-草稿 → 2-已发布 → 3-已下线。发布时会把内容推送到全部租户的通知中心
 * （customer_db.notification，notify_type=5-公告）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("announcement")
public class Announcement {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String title;

    private String content;

    /** 类型码：1-系统公告、2-产品更新、3-维护通知 */
    private Integer announceType;

    /** 状态码：1-草稿、2-已发布、3-已下线 */
    private Integer status;

    private LocalDateTime publishTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private String creator;

    private String editor;

    @TableField("is_deleted")
    private Boolean deleted;
}
