package io.supportflow.ops.dto;

import java.util.Map;

/**
 * 官网配置编辑器的全部状态。
 *
 * <p>{@code draft} / {@code published} / {@code defaults} 里的内容是**运营自己编的 JSON 区块**
 * （hero、pricing、faq…），字段由 {@code site-config.json} 决定、官网前端按字段覆盖，
 * 不是服务端能固定下来的 schema，所以这三项保持 Map；外层状态是固定的，抽成实体。</p>
 */
public record SiteConfigEditorVO(Map<String, Object> draft, Map<String, Object> published,
                                 boolean dirty, int version, int draftVersion,
                                 String publishTime, String updateTime, String editor,
                                 Map<String, Object> defaults) {
}
