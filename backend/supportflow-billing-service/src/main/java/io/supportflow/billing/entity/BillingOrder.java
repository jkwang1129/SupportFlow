package io.supportflow.billing.entity;

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
 * billing_order 订单表
 * 状态机：1-待支付 → 2-已支付（支付成功即写 pay_time 并通知 tenant 生效）/ 3-已取消 / 4-已退款。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("billing_order")
public class BillingOrder {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String tenantCode;

    private String orderNo;

    private Long planId;

    /** 周期码：1-年付、2-月付 */
    private Integer cycle;

    private BigDecimal amount;

    /** 1-待支付、2-已支付、3-已取消、4-已退款 */
    private Integer status;

    /** 支付方式码：1-支付宝、2-微信、3-对公转账 */
    private Integer payMethod;

    private LocalDateTime payTime;

    /** 发票状态码：1-未申请、2-已申请、3-已开具 */
    private Integer invoiceStatus;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private String creator;

    private String editor;

    @TableField("is_deleted")
    private Boolean deleted;
}
