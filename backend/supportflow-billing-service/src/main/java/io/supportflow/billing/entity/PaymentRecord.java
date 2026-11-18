package io.supportflow.billing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * payment_record 收款流水（**只追加**：收到的钱是一条事实，不改不删）。
 *
 * <p>与订单表的区别：订单是"要收多少钱"的意向（可能待支付、可能取消），
 * 收款流水是"真的收到了多少"，一笔一条，带支付方式、流水号、到账时间。
 * 平台「收款流水」页读的就是它——对账要的是这个口径。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("payment_record")
public class PaymentRecord {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String tenantCode;

    /** 订单 ID：账期账单收款没有订单，为 null */
    private Long orderId;

    /** 业务类型：1-订单（预付费套餐）、2-账期账单（后付费超量） */
    private Short bizType;

    /** 业务号：订单号 / 账单号（与 biz_type 一起唯一） */
    private String bizNo;

    /** 支付流水号：平台生成的收款单号，或三方回调里的流水号（唯一） */
    private String payNo;

    /** 三方口径的支付方式名（ALIPAY / WECHAT / CORP / MANUAL） */
    private String payMethod;

    /** 支付方式码：1-支付宝、2-微信、3-对公（与 billing_order.pay_method 同一套） */
    private Short payMethodCode;

    private BigDecimal amount;

    /** 状态：1-处理中、2-成功、3-失败 */
    private Short status;

    private LocalDateTime payTime;

    /** 三方回调原文（真实网关接入后存原文，出了问题能回溯） */
    private String callbackRaw;

    private String remark;

    private LocalDateTime createTime;

    private String creator;

    private String editor;
}
