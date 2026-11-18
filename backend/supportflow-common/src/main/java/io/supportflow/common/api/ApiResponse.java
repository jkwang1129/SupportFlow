package io.supportflow.common.api;

import io.supportflow.common.context.TraceContext;

import java.time.Instant;

/**
 * 统一响应体。
 *
 * <p>{@code requestId} 由 {@link TraceContext} 自动填：调用链号一次请求一个
 * （前端 → 网关 → Java → AI 服务全程透传），所以这里不用每个 Controller 手动传。</p>
 */
public record ApiResponse<T>(int code, String message, T data, String requestId, long timestamp) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), data,
                TraceContext.get(), Instant.now().toEpochMilli());
    }

    public static <T> ApiResponse<T> ok() {
        return ok(null);
    }

    public static <T> ApiResponse<T> fail(ResultCode rc) {
        return new ApiResponse<>(rc.getCode(), rc.getMessage(), null,
                TraceContext.get(), Instant.now().toEpochMilli());
    }

    public static <T> ApiResponse<T> fail(int code, String message) {
        return new ApiResponse<>(code, message, null, TraceContext.get(), Instant.now().toEpochMilli());
    }
}
