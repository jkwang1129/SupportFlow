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
 * quick_reply 快捷话术（工作台 Alt+1~9）。
 *
 * <p>表在基线 customer_db.sql 里就有了（一直没人用），这一版把它接到坐席工作台：
 * 启用中的话术按「使用次数倒序 + sort_no」排，前 9 条依次绑定 Alt+1 ~ Alt+9。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("quick_reply")
public class QuickReply {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String tenantCode;

    /** 话术标题（如：问候语） */
    private String title;

    /** 话术内容（发到会话里的原文） */
    private String content;

    /** 分组（如：售前 / 售后 / 致歉） */
    private String category;

    private Integer sortNo;

    private Boolean isEnabled;

    /** 被坐席使用次数：发送时累加，列表按它排序 */
    private Integer useCount;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private String creator;
    private String editor;

    @TableField("is_deleted")
    private Boolean deleted;
}
