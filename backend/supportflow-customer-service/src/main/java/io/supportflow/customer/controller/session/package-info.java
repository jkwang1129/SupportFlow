/**
 * 会话域 HTTP 接口：会话、消息、附件、坐席状态、技能组、快捷回复与控制台策略。
 *
 * <p>controller 这一层只做参数校验、登录上下文读取与结果包装，真正的业务规则在
 * {@code io.supportflow.customer.service.session} 下；放在一起是为了让"改会话相关的接口"
 * 不用在一堆 controller 里翻找。</p>
 */
package io.supportflow.customer.controller.session;
