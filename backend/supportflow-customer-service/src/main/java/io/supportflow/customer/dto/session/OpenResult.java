package io.supportflow.customer.dto.session;

import java.time.LocalDateTime;

/** 访客开会话结果 */
public record OpenResult(
        String sessionNo,
        String customerNo,
        String customerName,
        String visitorToken,
        LocalDateTime tokenExpireAt,
        int sessionStatus,
        boolean created,
        /** 访客身份令牌（长期）：前端保存，下次打开时回传以复用客户档案 */
        String visitorIdentityToken,
        /**
         * 机器人显示名（租户配置的 bot_name）。
         *
         * <p>客户端的消息标签用它，而不是写死"机器人"——真实客服系统给客户看的是
         * 助手名字（店小蜜、小云…），不是"你在跟机器人说话"这种提醒。</p>
         */
        String botName,
        /** 机器人头像地址（租户配置）；为空时前端用内置默认头像 */
        String botAvatarUrl
) {
}
