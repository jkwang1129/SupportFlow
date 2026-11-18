package io.supportflow.tenant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * tenant_usage_daily 租户日用量。
 *
 * <p>网关每 5 秒批量上报一次（不是每请求写库），平台侧展示"今日请求 / 今日被限流"，
 * 后续计费篇也会读它。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("tenant_usage_daily")
public class TenantUsageDaily {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String tenantCode;

    private LocalDate statDate;

    private Long requestCount;

    private Long rejectedCount;

    private Long sessionCount;

    private Long aiCallCount;

    private LocalDateTime updateTime;
}
