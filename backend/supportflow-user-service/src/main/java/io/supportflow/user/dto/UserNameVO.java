package io.supportflow.user.dto;

/**
 * 用户 ID → 姓名。
 *
 * <p>以前这个接口直接回 {@code Map<String,String>}（键是用户 ID）；键是数据不是字段，
 * 前端/调用方拿到的是一坨没有结构的字典。现在改成列表，由调用方自己组装 Map。</p>
 */
public record UserNameVO(String id, String name) {
}
