package io.supportflow.customer.dto.bot;

/**
 * 机器人档案：客户侧显示什么名字、开场说什么。
 *
 * <p>为什么客户侧要用"机器人的名字"而不是"机器人"三个字：真实客服系统（企业微信、
 * 京东、淘宝店小蜜、Intercom…）给客户看的是**品牌/助手名字**，客户知道对面是什么，
 * 但不需要每条消息都被提醒"你在跟机器人说话"。所以名字来自配置（bot_name），
 * 缺省时才退回中性的"智能客服"。</p>
 */
public record BotProfile(String botName, String welcomeMessage,
                         /** 机器人头像地址（租户自己的图）；为空时前端用内置默认头像 */
                         String avatarUrl) {
    public static final BotProfile EMPTY = new BotProfile(null, null, null);
}
