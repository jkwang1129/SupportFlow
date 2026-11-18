package io.supportflow.customer.event;

import io.supportflow.customer.service.session.SessionService;

/**
 * 消息落库事件。
 *
 * <p>谁在用：公众号渠道要把"机器人 / 坐席的回复"通过微信客服消息推回给客户。
 * 直接在 SessionService 里写微信逻辑会把核心链路和渠道耦合在一起，
 * 所以核心只管发事件，渠道侧自己去订阅（这也是 App / 小程序做原生通道时的同一个入口）。</p>
 *
 * @param tenantCode 租户
 * @param sessionNo  会话号
 * @param senderType 1-客户 2-坐席 3-机器人 4-系统
 * @param msgType    1-文本 2-图片
 * @param content    文本内容（图片为空）
 * @param visibleTo  1-双方可见 2-仅坐席可见（内部备注不能被推到客户微信里）
 */
public record SessionMessageEvent(String tenantCode, String sessionNo, int senderType,
                                  int msgType, String content, int visibleTo) {
}
