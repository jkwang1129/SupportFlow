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
 * site_config 官网配置（一个区块一行，按版本号攒历史）。
 *
 * <p>表在基线脚本里就有（{@code schema/ops_db.sql}），一直没人用；这次把它接上代码。
 * 一个区块一行有两个好处：官网侧按区块覆盖（改 FAQ 不动 Hero），发布历史天然留痕
 * （每发一次版本号 +1，旧版本行不删）。</p>
 *
 * <p>版本号与状态的约定：<b>草稿永远是"已发布最大版本 + 1"</b>，发布时把这一版草稿行
 * 原地改成"已发布"——因此 {@code (section_key, version_no)} 的唯一键永远不会撞。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
/** autoResultMap = true：config_json 用了自定义 typeHandler，查询时才会走它 */
@TableName(value = "site_config", autoResultMap = true)
public class SiteConfig {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 区块标识：hero / trust / features / showcase / solutions / cases / statband / pricing / faq / cta / footer */
    private String sectionKey;

    /**
     * 区块内容（JSON）：列是 {@code json}，必须走 {@link JsonStringTypeHandler}，
     * 否则 Postgres 报 "column is of type json but expression is of type character varying"。
     */
    @TableField(value = "config_json", typeHandler = JsonStringTypeHandler.class)
    private String configJson;

    /** 配置版本：草稿是"下一版"，发布后原地变成正式版本 */
    private Integer versionNo;

    /** 1-草稿、2-已发布（与 ck_site_config_status 对齐） */
    private Integer status;

    private LocalDateTime publishedAt;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private String creator;

    private String editor;

    @TableField("is_deleted")
    private Boolean deleted;
}
