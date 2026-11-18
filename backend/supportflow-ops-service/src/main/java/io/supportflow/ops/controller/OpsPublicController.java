package io.supportflow.ops.controller;

import io.supportflow.common.api.ApiResponse;
import io.supportflow.ops.service.SiteConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 官网公开接口：把「官网配置」下发给官网项目。
 *
 * <p>官网（supportflow-official）是独立部署的静态站点，打开时拉这份配置来覆盖默认文案
 * （hero 标题、统计数字、定价说明、FAQ、页脚版权…）。数据源在 ops_db.site_config：
 * 平台侧「官网配置」页保存草稿、点发布，访客下次打开官网就是新的；
 * 没发布过（或某区块没改过）就用 resources/site-config.json 的出厂文案兜底。</p>
 *
 * <p>为什么放 ops：官网配置属于平台运营范畴（与租户数据无关），不该混在业务服务里。</p>
 */
@RestController
@RequestMapping("/api/ops/public")
public class OpsPublicController {

    private static final Logger log = LoggerFactory.getLogger(OpsPublicController.class);

    private final SiteConfigService siteConfigService;

    public OpsPublicController(SiteConfigService siteConfigService) {
        this.siteConfigService = siteConfigService;
    }

    /** 官网配置：已发布版本覆盖在出厂文案之上（原样返回 JSON 对象，官网按字段覆盖） */
    @GetMapping("/site-config")
    public ApiResponse<Map<String, Object>> siteConfig() {
        try {
            return ApiResponse.ok(siteConfigService.publishedOrDefaults());
        } catch (Exception e) {
            // 配置读不到不能让官网白屏：返回空对象，官网会用静态默认文案
            log.warn("读取官网配置失败：{}", e.getMessage());
            return ApiResponse.ok(Map.of());
        }
    }
}
