package io.supportflow.customer.controller.open;

import io.supportflow.common.api.ApiResponse;
import io.supportflow.customer.dto.open.OpenApiOverviewVO;
import io.supportflow.customer.service.open.OpenApiService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 开放 API（第一版）：企业自有系统用 API 密钥调用，不需要人登录后台。
 *
 * <p>换身份的方式：请求头带上 {@code X-Api-Key}（平台「安全中心 → API 密钥」生成），
 * 服务端拿它去 ops 校验 → 拿到<b>租户编码</b>，之后所有查询都限定在这个租户内——
 * 与用户 JWT 那条链路完全分开：密钥是给机器用的、可长期有效、可随时停用、可限权。</p>
 *
 * <p><b>第一版只开一个只读聚合接口</b>：出的是"多少条"这类统计，不出明细。
 * 对外开放的接口每多一个就多一处越权风险，宁可先窄后宽。</p>
 */
@RestController
@RequestMapping("/api/open/v1")
public class OpenApiController {

    private final OpenApiService openApiService;

    public OpenApiController(OpenApiService openApiService) {
        this.openApiService = openApiService;
    }

    /**
     * 租户运营概览（只读聚合）。
     *
     * <p>鉴权（X-Api-Key 校验）与统计口径在 {@link OpenApiService} 里，
     * 控制器只收请求头、包响应。</p>
     */
    @GetMapping("/overview")
    public ApiResponse<OpenApiOverviewVO> overview(
            @RequestHeader(value = "X-Api-Key", required = false) String apiKey
    ) {
        return ApiResponse.ok(openApiService.overview(apiKey));
    }
}
