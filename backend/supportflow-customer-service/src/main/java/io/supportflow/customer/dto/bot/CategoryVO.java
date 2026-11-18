package io.supportflow.customer.dto.bot;

/** 分类视图 */
public record CategoryVO(String id, String parentId, String name, int sortNo, long docCount) {
}
