package io.supportflow.ops.dto;

/** 导出申请入参（名字不能叫 RequestBody：会和 Spring 的注解重名，编译期直接报错） */
public record ExportRequestBody(String tenantCode, int exportType, String scopeJson,
                                Long requesterId, String requesterName) {
}
