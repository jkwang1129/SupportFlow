package io.supportflow.ops.dto;

import java.util.List;

/**
 * 公告分页。
 */
public record AnnouncementPageVO(long total, int page, int pageSize,
                                 List<AnnouncementRowVO> list) {
}
