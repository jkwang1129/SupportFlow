package io.supportflow.customer.entity;

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

// 渠道原生接入配置（App / 400 热线 / 邮件）。各渠道配置项差异大，统一放 config_json。
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
/** autoResultMap = true：config_json 用了自定义 typeHandler，查询时才会走它 */
@TableName(value = "channel_native_config", autoResultMap = true)
public class ChannelNativeConfig {
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;
    private String tenantCode;
    private Long channelId;
    /**
     * 配置项 JSON：列是 {@code jsonb}，必须走 {@link JsonStringTypeHandler}，
     * 否则保存配置时 Postgres 报 "column config_json is of type jsonb but expression is of type character varying"。
     */
    @TableField(value = "config_json", typeHandler = JsonStringTypeHandler.class)
    private String configJson;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private String creator;
    private String editor;
    @TableField("is_deleted")
    private Boolean deleted;
}
