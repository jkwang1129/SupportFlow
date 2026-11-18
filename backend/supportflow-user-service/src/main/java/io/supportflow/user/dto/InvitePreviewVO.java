package io.supportflow.user.dto;

import java.time.LocalDateTime;

/**
 * 公共邀请预览。
 */
public record InvitePreviewVO(
        String inviteCode,
        String roleCode,
        String roleName,
        String inviterName,
        String phoneMasked,
        String emailMasked,
        LocalDateTime expireTime
) {
}
