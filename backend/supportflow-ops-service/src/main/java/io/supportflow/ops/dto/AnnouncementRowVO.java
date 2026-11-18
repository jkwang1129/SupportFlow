package io.supportflow.ops.dto;

import java.time.LocalDateTime;

/**
 * 平台公告列表的一行。
 */
public record AnnouncementRowVO(String id, String title, String content, Integer announceType,
                                Integer status, LocalDateTime publishTime,
                                LocalDateTime createTime, String creator, String editor) {
}
