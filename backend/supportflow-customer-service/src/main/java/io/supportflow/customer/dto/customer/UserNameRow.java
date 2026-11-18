package io.supportflow.customer.dto.customer;

/**
 * user-service 内部接口「批量查姓名」返回的一项（消费方自己的镜像）。
 *
 * <p>两个服务是两个独立部署，契约靠 HTTP 约定而不是共享类；这里按同样的字段
 * 建一份自己的类型，和 {@link ExportRequestVO} 一个思路。</p>
 */
public record UserNameRow(String id, String name) {
}
