package io.supportflow.common.api;

import java.util.List;

/**
 * 分页返回的固定信封：{@code {total, page, pageSize, list}}。
 *
 * <p>以前每个分页接口各写一个 Map，字段名只能靠对文档；抽成泛型记录之后，
 * 「页码字段叫 page 还是 pageNo」这种问题在编译期就不会再出现。</p>
 *
 * @param <T> 列表元素类型
 */
public record PageResult<T>(long total, int page, int pageSize, List<T> list) {
}
