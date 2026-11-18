package io.supportflow.common.auth;


/**
 * 每个服务自己的登录态解析器（各服务密钥配置不同，解析器也就各有一份）。
 *
 * <p>实现类交给 {@code LoginUserFilter} 在请求入口调一次，控制器不再碰 Authorization 头。
 * 实现里不要抛异常：解析不出来就返回
 * {@link AuthState#failed}——有些接口允许匿名访问，
 * 拦不拦该由控制器调 {@code require()} 决定，而不是在过滤器里一刀切 401。</p>
 */
public interface LoginUserResolver {

    /**
     * 解析 Authorization 头。
     *
     * @param authorization 原始请求头（可能是 null / 空串 / 不带 Bearer 前缀）
     * @return 成功给 user，失败给原因；都不为 null
     */
    AuthState resolve(String authorization);
}
