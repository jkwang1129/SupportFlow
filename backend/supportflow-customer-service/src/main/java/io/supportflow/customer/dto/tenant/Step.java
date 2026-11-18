package io.supportflow.customer.dto.tenant;

/** 一个升级步骤 = 一个增量脚本 */
public record Step(int version, String script, String title) {
}
