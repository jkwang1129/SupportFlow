package io.supportflow.billing.entity;

import io.supportflow.common.config.JsonStringTypeHandler;
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
 * payment_config 支付渠道配置（一个渠道一行：1-支付宝、2-微信、3-对公转账）。
 *
 * <p>表在最初的 billing_db.sql 里就建好了（一路没人用），这次把它接上平台设置页。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
/** autoResultMap = true：config_json 用了自定义 typeHandler，查询时才会走它 */
@TableName(value = "payment_config", autoResultMap = true)
public class PaymentConfig {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 1-支付宝、2-微信、3-对公转账 */
    private Integer payChannel;

    /** 商户号（对公转账可空） */
    private String merchantId;

    /**
     * 渠道扩展参数（JSON）。
     *
     * <p>对公转账存账户信息：{@code {"accountName":"…","accountNo":"…","bankName":"…"}}；
     * 在线支付留 {@code {}}。列是 {@code json}，靠 {@link JsonStringTypeHandler} 绑定参数。</p>
     */
    @TableField(value = "config_json", typeHandler = JsonStringTypeHandler.class)
    private String configJson;

    private Boolean isEnabled;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private String creator;

    private String editor;

    @TableField("is_deleted")
    private Boolean deleted;
}
