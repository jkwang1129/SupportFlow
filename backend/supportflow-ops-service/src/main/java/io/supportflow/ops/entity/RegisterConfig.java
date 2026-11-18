package io.supportflow.ops.entity;

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
 * register_config 平台注册与审核策略（全平台一条）。
 *
 * <p>只有一行数据（首次保存时创建）；原型上的"注册与审核"页就是它。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
/** autoResultMap = true：config_json 用了自定义 typeHandler，查询时才会走它 */
@TableName(value = "register_config", autoResultMap = true)
public class RegisterConfig {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 是否开放企业注册（总开关） */
    private Boolean allowRegister;

    /** 新注册企业是否需要运营审核 */
    private Boolean needReview;

    /** 新租户试用时长（天） */
    private Short trialDays;

    /**
     * 扩展配置（JSON）：邮箱域名白名单、是否双重验证等"列还没必要单开"的项。
     *
     * <p>列是 {@code json}，靠 {@link JsonStringTypeHandler} 绑定参数——
     * 不写它 Postgres 会报 "column is of type json but expression is of type character varying"。</p>
     */
    @TableField(value = "config_json", typeHandler = JsonStringTypeHandler.class)
    private String configJson;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private String creator;

    private String editor;

    @TableField("is_deleted")
    private Boolean deleted;
}
