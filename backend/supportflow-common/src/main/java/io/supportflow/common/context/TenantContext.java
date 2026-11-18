package io.supportflow.common.context;

import java.lang.ScopedValue.CallableOp;

/**
 * 租户上下文（{@link ScopedValue}，JDK 25 正式 API）。网关解析 X-Tenant-Code 后注入，业务侧使用。
 *
 * <p>绑定只在 {@link #bind} 的作用域内有效，出了作用域自动失效——不需要 ThreadLocal 那套
 * "set 完记得 remove"的纪律，也就不会出现"线程池复用把上个租户带进下一个请求"。</p>
 *
 * <p>RLS（{@code TenantRlsConfig}）借连接时会读它：上下文为空就写空串（而不是不写），
 * 所以"没绑"和"绑了空串"对 RLS 是一回事。</p>
 */
public final class TenantContext {

    /**
     * 租户编码的请求头名（网关 ↔ 各服务 ↔ supportflow-ai 的约定）。
     *
     * <p>以前这个名字在本类里声明了却没人用，实际是 11 处各写一遍 {@code "X-Tenant-Code"}
     * 字面量；改协议要满仓库找字符串。现在统一引用它。</p>
     *
     * <p>调用链号的头名不在这里——那是 {@link TraceContext#HEADER_REQUEST_ID} 的事，
     * 本类曾经重复声明过一份，容易改了一处漏一处，已删。</p>
     */
    public static final String HEADER_TENANT_CODE = "X-Tenant-Code";

    /**
     * 包级可见：{@link LoginUserContext#bind} 要把"登录态 + 租户"一次性绑进同一个作用域，
     * 免得入口过滤器里嵌两层 bind。
     */
    static final ScopedValue<String> CURRENT = ScopedValue.newInstance();

    private TenantContext() {
    }

    public static String get() {
        return CURRENT.isBound() ? CURRENT.get() : null;
    }

    /**
     * 把租户绑进作用域并执行 action。
     *
     * <p>给"没有请求上下文的线程"用：定时任务、异步任务在进入时显式绑一次，
     * 而不是像以前那样 set 完再在 finally 里 clear（甚至还要保存/还原上一个值）。</p>
     */
    public static <R, X extends Throwable> R bind(String tenantCode, CallableOp<? extends R, X> action)
            throws X {
        return ScopedValue.where(CURRENT, tenantCode).call(action);
    }
}
