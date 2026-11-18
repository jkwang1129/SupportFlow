package io.supportflow.common.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Servlet 请求里那几个"每个控制器/客户端都要自己抄一遍"的取值：客户端 IP、来源域名、User-Agent。
 *
 * <p>为什么集中放这里：这几段逻辑看起来很短，但每抄一份就会漏一个分支——
 * 比如有的地方只取 {@code X-Forwarded-For} 忘了 {@code X-Real-IP}，有的地方忘了网关转发后
 * {@code remoteAddr} 其实是网关地址。抄散的后果是"同一个客户端在不同接口里 IP 不一样"，
 * 对限流与安全事件排查是灾难。</p>
 *
 * <p><b>WebFlux 侧（网关）不适用</b>：那边没有 Servlet 请求，用
 * {@code io.supportflow.gateway.support.RequestIps}。</p>
 */
public final class RequestUtils {

    private RequestUtils() {
    }

    /**
     * 客户端 IP：网关转发时优先取 {@code X-Forwarded-For} 的第一段（最靠近客户端的那一跳），
     * 其次是反代常写的 {@code X-Real-IP}，最后才退回 socket 地址。
     */
    public static String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = header(request, "X-Forwarded-For");
        if (forwarded != null) {
            // XFF 是 "client, proxy1, proxy2"：第一段才是客户端
            return forwarded.split(",")[0].trim();
        }
        String realIp = header(request, "X-Real-IP");
        return realIp != null ? realIp : request.getRemoteAddr();
    }

    /**
     * 请求来源：优先 {@code Origin}，取不到再退回 {@code Referer}
     * （老浏览器 / 同源请求可能没有 Origin）。
     *
     * <p>渠道域名白名单校验用的就是这个值，所以不能再往 {@code hostOf} 之前丢信息。</p>
     */
    public static String origin(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String origin = header(request, "Origin");
        return origin != null ? origin : header(request, "Referer");
    }

    /** User-Agent（没有就返回 null，别返回 ""——调用方多半要判空兜底） */
    public static String userAgent(HttpServletRequest request) {
        return header(request, "User-Agent");
    }

    /** 取请求头：空白当没有，返回值已 trim */
    public static String header(HttpServletRequest request, String name) {
        if (request == null || name == null) {
            return null;
        }
        String value = request.getHeader(name);
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * 从 Origin / Referer 里取出 host（全小写，不带端口、路径、协议）。
     *
     * <p>例：{@code https://Shop.Example.com:8443/cart} → {@code shop.example.com}。</p>
     */
    public static String hostOf(String origin) {
        String hostWithPort = hostWithPortOf(origin);
        if (hostWithPort == null) {
            return null;
        }
        int colon = hostWithPort.indexOf(':');
        return colon >= 0 ? hostWithPort.substring(0, colon) : hostWithPort;
    }

    /**
     * 从 Origin / Referer 里取出 host[:port]（全小写，不带协议与路径）。
     *
     * <p>白名单里写了端口（{@code localhost:5173}）时要按带端口比对，所以这个重载必须留着。</p>
     */
    public static String hostWithPortOf(String origin) {
        if (origin == null || origin.isBlank()) {
            return null;
        }
        String value = origin.trim().toLowerCase();
        int schemeIndex = value.indexOf("://");
        if (schemeIndex >= 0) {
            value = value.substring(schemeIndex + 3);
        }
        int pathIndex = value.indexOf('/');
        if (pathIndex >= 0) {
            value = value.substring(0, pathIndex);
        }
        // 形如 user:pass@host 的地址，只取 @ 后面的 host
        int at = value.indexOf('@');
        if (at >= 0) {
            value = value.substring(at + 1);
        }
        return value.isBlank() ? null : value;
    }
}
