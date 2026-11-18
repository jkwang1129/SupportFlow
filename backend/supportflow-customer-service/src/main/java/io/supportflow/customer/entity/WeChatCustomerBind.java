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
 * 微信 openid 与客户档案的绑定。
 *
 * <p>没有这张表，同一个人每次进公众号都会被当成新客户（微信只给 openid，
 * 我们的客户身份是 customer_id）。绑定之后，公众号里的会话、轨迹、满意度都落在这个客户身上。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("wechat_customer_bind")
public class WeChatCustomerBind {
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;
    private String tenantCode;
    private String appId;
    private String openid;
    private Long customerId;
    private String customerNo;
    private String customerName;
    /** 最近一次进线所在的会话号（复用未结束的会话，避免每来一条消息就新开会话） */
    private String sessionNo;
    /** 1-已关注 2-已取关（取关后保留绑定，重新关注还是同一个人） */
    private Integer subscribeStatus;
    private LocalDateTime bindTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    @TableField("is_deleted")
    private Boolean deleted;
}
