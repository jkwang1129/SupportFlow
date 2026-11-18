package io.supportflow.ops.dto;

/**
 * 发布 / 下线公告的回执。
 *
 * @param delivered 推送到几个租户的通知中心；-1 表示推送失败（不影响"已发布"这个事实）
 */
public record AnnouncementStatusVO(String id, int status, int delivered) {
}
