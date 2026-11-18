package io.supportflow.billing.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 某一天的用量（大屏折线图的一行）。
 *
 * <p>JSON 字段名保持数据库列的下划线写法：这个列表原来就是 MyBatis 直接返回的 Map，
 * 前端拿到的键是 {@code stat_date} / {@code ai_calls} / {@code storage_gb}，
 * 这里用 {@link JsonProperty} 钉住，避免改成实体类时把报文悄悄换掉。</p>
 */
public record UsageDailyVO(@JsonProperty("stat_date") LocalDate statDate,
                           @JsonProperty("sessions") Long sessions,
                           @JsonProperty("messages") Long messages,
                           @JsonProperty("ai_calls") Long aiCalls,
                           @JsonProperty("seats") Integer seats,
                           @JsonProperty("storage_gb") BigDecimal storageGb) {
}
