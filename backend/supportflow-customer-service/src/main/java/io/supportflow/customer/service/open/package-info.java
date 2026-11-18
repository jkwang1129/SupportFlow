/**
 * 开放 API 域业务：企业自有系统按 API 密钥调用，不需要人登录后台。
 *
 * <p>密钥校验走 ops 安全中心，通过后所有查询都限定在密钥归属的租户内，
 * 与用户 JWT 那条链路完全分开（给机器用、可长期有效、可随时停用）。</p>
 */
package io.supportflow.customer.service.open;
