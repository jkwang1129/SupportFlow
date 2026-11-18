package io.supportflow.ops.controller;

import io.supportflow.common.context.LoginUserContext;
import io.supportflow.common.api.ApiResponse;
import io.supportflow.common.api.IdVO;
import io.supportflow.common.auth.LoginUser;
import io.supportflow.common.exception.BizException;
import io.supportflow.ops.dto.AnnouncementBody;
import io.supportflow.ops.dto.AnnouncementPageVO;
import io.supportflow.ops.dto.AnnouncementStatusVO;
import io.supportflow.ops.dto.RegisterConfigBody;
import io.supportflow.ops.dto.RegisterConfigVO;
import io.supportflow.ops.service.PlatformSettingsService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台设置（原型「平台设置」）：注册与审核 + 平台公告。
 *
 * <p>另外两块（套餐价格 / 支付配置）在 billing-service——钱相关的库在那边，
 * 页面把四个区块拼在一起，后端按域各管各的。</p>
 */
@RestController
@RequestMapping("/api/ops/platform/settings")
public class PlatformSettingsController {

    private final PlatformSettingsService settingsService;

    public PlatformSettingsController(PlatformSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    /** 读注册审核策略 */
    @GetMapping("/register-config")
    public ApiResponse<RegisterConfigVO> registerConfig() {
        requirePlatform();
        return ApiResponse.ok(settingsService.registerConfig());
    }

    /** 保存注册审核策略 */
    @PutMapping("/register-config")
    public ApiResponse<RegisterConfigVO> saveRegisterConfig(
            @RequestBody RegisterConfigBody body
    ) {
        LoginUser operator = requirePlatform();
        return ApiResponse.ok(settingsService.saveRegisterConfig(operator, body));
    }

    /** 公告列表 */
    @GetMapping("/announcements")
    public ApiResponse<AnnouncementPageVO> announcements(
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Integer announceType,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize
    ) {
        requirePlatform();
        return ApiResponse.ok(settingsService.announcements(status, announceType, keyword,
                Math.max(1, page), Math.min(Math.max(1, pageSize), 100)));
    }

    /** 新建公告（草稿） */
    @PostMapping("/announcements")
    public ApiResponse<IdVO> createAnnouncement(
            @RequestBody AnnouncementBody body
    ) {
        LoginUser operator = requirePlatform();
        return ApiResponse.ok(settingsService.saveAnnouncement(operator, null, body));
    }

    /** 修改公告（草稿态才能改） */
    @PutMapping("/announcements/{id}")
    public ApiResponse<IdVO> updateAnnouncement(
            @PathVariable long id,
            @RequestBody AnnouncementBody body
    ) {
        LoginUser operator = requirePlatform();
        return ApiResponse.ok(settingsService.saveAnnouncement(operator, id, body));
    }

    /** 发布（status=2）/ 下线（status=3）：发布会广播到全部租户的通知中心 */
    @PostMapping("/announcements/{id}/status")
    public ApiResponse<AnnouncementStatusVO> changeAnnouncementStatus(
            @PathVariable long id,
            @RequestParam int status
    ) {
        LoginUser operator = requirePlatform();
        return ApiResponse.ok(settingsService.changeAnnouncementStatus(operator, id, status));
    }

    /** 删除公告 */
    @DeleteMapping("/announcements/{id}")
    public ApiResponse<Void> removeAnnouncement(
            @PathVariable long id
    ) {
        LoginUser operator = requirePlatform();
        settingsService.removeAnnouncement(operator, id);
        return ApiResponse.ok();
    }

    private LoginUser requirePlatform() {
        LoginUser user = LoginUserContext.require();
        if (user.userType() != 1) {
            throw new BizException(40302, "只有平台账号能改平台设置");
        }
        return user;
    }
}
