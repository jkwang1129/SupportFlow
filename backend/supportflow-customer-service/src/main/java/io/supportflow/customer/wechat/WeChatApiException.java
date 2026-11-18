package io.supportflow.customer.wechat;

import io.supportflow.common.exception.BizException;

/**
 * 微信接口异常（带 errcode）。
 *
 * <p>为什么单独一个类型：订阅消息的失败要**记进发送日志**，日志里得存错误码——
 * 只从字符串里抠数字太脆，这里直接把码带出来。</p>
 */
public class WeChatApiException extends BizException {

    private final int errcode;

    public WeChatApiException(int errcode, String message) {
        super(50002, message);
        this.errcode = errcode;
    }

    public int getErrcode() {
        return errcode;
    }
}
