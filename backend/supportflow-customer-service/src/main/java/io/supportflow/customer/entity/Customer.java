package io.supportflow.customer.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * customer 客户表实体（访客进门后先建档，再开会话）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("customer")
public class Customer {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String tenantCode;

    private String customerNo;

    private String name;

    private String phone;

    /** 会员等级码：1-普通、2-银卡、3-金卡、4-铂金、5-企业 */
    private Integer level;

    /** 来源渠道 */
    private String channel;

    private Integer ordersCount;

    private BigDecimal totalValue;

    private Integer points;

    private BigDecimal csat;

    private Integer sentiment;

    private LocalDateTime lastActive;

    /** 客户备注（坐席可见的内部说明，不展示给客户） */
    private String remark;

    /** 累计会话数（冗余字段，列表排序与筛选用） */
    private Integer sessionCount;

    /** 累计工单数 */
    private Integer ticketCount;

    /** 最近一次会话时间 */
    private LocalDateTime lastSessionAt;

    /** 风险等级码：1-正常、2-关注、3-风险 */
    private Integer riskLevel;

    /** 重复客户被合并到哪个客户编号（自身软删，便于回溯） */
    private String mergedInto;

    /** 匿名化时间：姓名 / 手机号 / 备注已抹除，会话与统计保留 */
    private LocalDateTime anonymizedAt;

    /** 客户类型码：1-个人客户、2-企业客户（B 端采购，与会员等级无关） */
    private Integer customerType;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private String creator;

    private String editor;

    @TableField("is_deleted")
    private Boolean deleted;
}
