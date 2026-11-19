package io.supportflow.customer.controller.bot;

import io.supportflow.common.context.LoginUserContext;
import io.supportflow.common.api.ApiResponse;
import io.supportflow.common.auth.LoginUser;
import io.supportflow.customer.dto.bot.FileContent;
import io.supportflow.customer.dto.bot.UploadResult;
import io.supportflow.customer.service.bot.BotAvatarService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 机器人头像：上传（要登录，租户从令牌取）与读取（免登录，给访客端当 img src）。
 *
 * <p>上传完拿到的 {@code url} 就是「智能机器人 → 机器人设置 → 机器人头像」里那个值，
 * 存进 {@code bot_setting.avatar_url}；访客端开会话时由后端下发，气泡与窗口头部都用它。</p>
 */
@RestController
@RequestMapping("/api/customer/bot/avatar")
public class BotAvatarController {

    private final BotAvatarService avatarService;

    public BotAvatarController(BotAvatarService avatarService) {
        this.avatarService = avatarService;
    }

    /**
     * 上传头像（企业侧登录态；PNG / JPG / WEBP，≤2MB）
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<UploadResult> upload(
            @RequestParam("file") MultipartFile file
    ) {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(avatarService.upload(user, file));
    }

    /**
     * 读头像（免登录：访客端要显示，靠不可猜的文件 ID 当凭据）
     */
    @GetMapping("/{fileId}")
    public ResponseEntity<byte[]> load(@PathVariable long fileId) {
        FileContent content = avatarService.load(fileId);
        return ResponseEntity.ok()
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=3600")
                .contentType(MediaType.parseMediaType(content.mimeType()))
                .body(content.bytes());
    }
}
