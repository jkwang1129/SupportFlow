package io.supportflow.ops.controller;

import io.supportflow.common.context.LoginUserContext;
import io.supportflow.common.api.ApiResponse;
import io.supportflow.common.auth.LoginUser;
import io.supportflow.common.exception.BizException;
import io.supportflow.ops.dto.SiteConfigEditorVO;
import io.supportflow.ops.dto.SitePreviewVO;
import io.supportflow.ops.service.SiteConfigService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 官网配置（原型「官网配置」）：可视化编辑 → 预览 → 发布。
 *
 * <p>公开读取在 {@link OpsPublicController}（官网项目拉的那份），这里只管编辑侧：
 * 读草稿与已发布、存草稿、发预览参数、发布、恢复默认。全部限平台账号。</p>
 */
@RestController
@RequestMapping("/api/ops/platform/site-config")
public class PlatformSiteConfigController {

    private final SiteConfigService siteConfigService;

    public PlatformSiteConfigController(SiteConfigService siteConfigService) {
        this.siteConfigService = siteConfigService;
    }

    /** 编辑器初始状态：草稿 + 已发布 + 有没有未发布的改动 + 版本与时间 */
    @GetMapping
    public ApiResponse<SiteConfigEditorVO> current() {
        requirePlatform();
        return ApiResponse.ok(siteConfigService.current());
    }

    /**
     * 保存草稿（不动线上）。
     *
     * <p>入参是「区块名 → 区块内容」的配置文档，区块清单见
     * {@link io.supportflow.ops.service.SiteConfigService} 的 SECTION_KEYS（与
     * {@code site-config.json} 一致），内容由运营自己在编辑器里编。
     * 这里用 Map 是刻意的——区块清单与"对象或数组"这层结构本来就是数据驱动的，
     * 服务端会拒收 unknown 区块、限制单区块体积与文案长度；包成实体反而要把这套
     * 校验拆成两半（一半靠 Jackson、一半留在服务里），错误提示也会变形。</p>
     */
    @PutMapping
    public ApiResponse<SiteConfigEditorVO> saveDraft(
            @RequestBody Map<String, Object> body
    ) {
        LoginUser operator = requirePlatform();
        return ApiResponse.ok(siteConfigService.saveDraft(operator, body));
    }

    /** 预览参数：草稿编码成官网认识的 ?site= 串 */
    @GetMapping("/preview")
    public ApiResponse<SitePreviewVO> preview() {
        requirePlatform();
        return ApiResponse.ok(new SitePreviewVO(siteConfigService.previewParam()));
    }

    /** 发布：把草稿变成线上版本（官网下次打开即生效） */
    @PostMapping("/publish")
    public ApiResponse<SiteConfigEditorVO> publish() {
        LoginUser operator = requirePlatform();
        return ApiResponse.ok(siteConfigService.publish(operator));
    }

    /** 恢复默认：草稿重置为出厂文案，线上不动 */
    @PostMapping("/reset")
    public ApiResponse<SiteConfigEditorVO> reset() {
        LoginUser operator = requirePlatform();
        return ApiResponse.ok(siteConfigService.resetDraft(operator));
    }

    private LoginUser requirePlatform() {
        LoginUser user = LoginUserContext.require();
        if (user.userType() != 1) {
            throw new BizException(40302, "只有平台账号能改官网配置");
        }
        return user;
    }
}
