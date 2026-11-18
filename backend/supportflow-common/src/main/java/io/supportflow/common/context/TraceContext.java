package io.supportflow.common.context;

import java.lang.ScopedValue.CallableOp;
import java.util.UUID;

/**
 * 调用链上下文（{@link ScopedValue}，JDK 25 正式 API）。
 *
 * <p>一次请求一个号：前端发起时带上 {@code X-Request-Id}，网关补齐并透传，
 * 各 Java 服务写进日志（MDC）与响应体（{@code ApiResponse.requestId}），
 * 调 AI 服务时继续带着它——AI 侧把同一个号写进 {@code ai_usage_record.request_id}。
 * 于是「页面报错 → 拿号 → 查到这次 AI 调用花了多少、慢在哪」是通的。</p>
 *
 * <p>绑定只在 {@link #bind} 的作用域内有效：出了作用域自动失效，不需要（也没法）
 * 手动清理——ThreadLocal 时代"忘了 remove 就把上个请求的号带给下一个请求"这类问题
 * 从结构上消失。</p>
 *
 * <p>仍是"同一线程内有效"：异步线程读不到，要自己显式绑定（与租户上下文同一套约定）。</p>
 */
public final class TraceContext {

    /** 与 AI 服务、网关约定一致的请求头名 */
    public static final String HEADER_REQUEST_ID = "X-Request-Id";

    /** 日志里的键名：logging pattern 里用 %X{traceId} 就能打出来 */
    public static final String MDC_KEY = "traceId";

    private static final ScopedValue<String> CURRENT = ScopedValue.newInstance();

    private TraceContext() {
    }

    /** 当前调用链号；不在请求线程里时为 null */
    public static String get() {
        return CURRENT.isBound() ? CURRENT.get() : null;
    }

    /** 没有就现生成一个（16 位十六进制，够短且不重复） */
    public static String getOrCreate() {
        String current = get();
        if (current != null && !current.isBlank()) {
            return current;
        }
        return newTraceId();
    }

    /**
     * 绑定调用链号并执行 action。只给入口过滤器用，业务代码不要调。
     *
     * @param action 作用域内的逻辑；出了这个作用域号就不可见了
     */
    public static <R, X extends Throwable> R bind(String traceId, CallableOp<? extends R, X> action)
            throws X {
        return ScopedValue.where(CURRENT, traceId).call(action);
    }

    public static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
