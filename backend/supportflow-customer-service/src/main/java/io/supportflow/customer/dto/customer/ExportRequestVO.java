package io.supportflow.customer.dto.customer;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * ops 侧「提交导出申请」的原始回执。
 *
 * @param failed 平台安全服务调不通时为 true；**必须能被上层认出来**——
 *               早期版本这里返回空 Map，控制器把它当成功回给企业，
 *               现象是"页面提示申请已提交、平台却看不到记录"，假成功比报错更糟
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExportRequestVO(String id, Integer status, Boolean duplicated, Boolean failed,
                              String reason) {
}
