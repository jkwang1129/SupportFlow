package io.supportflow.customer.dto.session;

import java.util.List;

/**
 * 保存话术后的回执：新建/修改的那条 ID + 最新列表（快捷键序号由服务端算，页面直接刷新）。
 */
public record QuickReplySaveVO(String id, List<QuickReplyVO> list) {
}
