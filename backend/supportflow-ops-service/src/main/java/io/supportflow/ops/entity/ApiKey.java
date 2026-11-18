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
 * api_key 对外开放 API 的密钥。

 * <p>库里**只存哈希**（被拖库也不能直接拿去调接口），生成时回显一次明文；
 * 页面靠 {@code key_prefix} 辨认是哪一把。校验在网关做：请求带 {@code X-Api-Key}，
 * 网关问 ops-service 要租户，然后把请求转成"该租户的内部调用"。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("api_key")
public class ApiKey {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String tenantCode;

    private String keyName;

    /** 密钥的 SHA-256 哈希（不存明文） */
    private String apiKey;

    /** 展示用前缀，如 yk_ab12cd34 */
    private String keyPrefix;

    /** 允许的权限范围（暂留，如 read） */
    private String permissions;

    /** 1-启用、2-停用 */
    private Short status;

    private Long callCount;

    private LocalDateTime lastUsedAt;

    private LocalDateTime expireTime;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private String creator;

    private String editor;

    @TableField("is_deleted")
    private Boolean deleted;
}
