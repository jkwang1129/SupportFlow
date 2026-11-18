package io.supportflow.customer.dto.customer;

/**
 * 提交「客户档案导出申请」的入参。
 *
 * <p>以前这里收的是 {@code Map<String,Object>}，控制器再 {@code body.get("keyword")}
 * 取值——传错键名不会有任何提示，只是"筛选条件神秘丢失"。改成实体后键名写错编译期就报，
 * 多传字段也不会被静默接受。</p>
 *
 * @param keyword 申请单上的筛选关键字；留空表示"全部客户"
 */
public record ExportRequestBody(String keyword) {
}
