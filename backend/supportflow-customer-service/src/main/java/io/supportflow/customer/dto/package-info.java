/**
 * 客户服务的请求 / 响应实体。
 *
 * <p>按业务域再分子包（session / ticket / customer / bot 等），和 controller、service 的分域保持一致；
 * 同一个域里的入参与出参放在一起，跨域引用也能一眼看出依赖方向。</p>
 */
package io.supportflow.customer.dto;
