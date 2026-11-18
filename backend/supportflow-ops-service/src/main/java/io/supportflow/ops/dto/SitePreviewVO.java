package io.supportflow.ops.dto;

/**
 * 官网配置预览参数。
 *
 * @param param 草稿的 base64url(UTF-8 JSON)，官网用 {@code ?site=} 直接吃
 */
public record SitePreviewVO(String param) {
}
