package io.supportflow.customer.controller.session;

import io.supportflow.common.api.ApiResponse;
import io.supportflow.common.context.TenantContext;
import io.supportflow.customer.security.ChannelTenantResolver;
import io.supportflow.customer.security.ChatAttachmentAccess;
import io.supportflow.common.exception.BizException;
import org.springframework.web.bind.annotation.RequestHeader;
import io.supportflow.customer.dto.session.FileContent;
import io.supportflow.customer.dto.session.UploadResult;
import io.supportflow.customer.service.session.SessionAttachmentService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
/**
 * 聊天图片附件：客户在访客窗口发图。
 *
 * <p>两个接口，分工不同：</p>
 *
 * <ul>
 *   <li><b>上传</b>：渠道密钥与访客会话或企业身份必须对应同一租户；</li>
 *   <li><b>读取</b>：验证访客会话或企业身份，并核对文件所属会话。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/customer/sessions/attachments")
public class SessionAttachmentController {

    private final SessionAttachmentService attachmentService;
    private final ChannelTenantResolver channelTenantResolver;
    private final ChatAttachmentAccess access;

    public SessionAttachmentController(SessionAttachmentService attachmentService, ChannelTenantResolver channelTenantResolver,
                                       ChatAttachmentAccess access) {
        this.attachmentService = attachmentService;
        this.channelTenantResolver = channelTenantResolver;
        this.access = access;
    }

    /**
     * 访客上传一张聊天图片（聊天窗口里点「发送图片」）。
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<UploadResult> upload(
            @RequestParam("appKey") String appKey,
            @RequestParam("sessionNo") String sessionNo,
            @RequestHeader(value = "X-Visitor-Token", required = false) String visitorToken,
            @RequestPart("file") MultipartFile file
    ) {
        String tenant = channelTenantResolver.resolve(appKey);
        if (!tenant.equals(access.tenant(sessionNo, visitorToken))) {
            throw new BizException(40301, "渠道与会话租户不一致");
        }
        return TenantContext.bind(tenant, () -> ApiResponse.ok(attachmentService.upload(tenant, sessionNo, file)));
    }

    /**
     * 按文件 ID 读取图片（消息气泡里直接当 img src 用）。
     */
    @GetMapping("/{fileId}")
    public ResponseEntity<byte[]> load(@PathVariable long fileId, @RequestParam String sessionNo,
            @RequestHeader(value = "X-Visitor-Token", required = false) String visitorToken) throws IOException {
        String tenant = access.tenant(sessionNo, visitorToken);
        FileContent content = TenantContext.bind(tenant, () -> attachmentService.load(tenant, sessionNo, fileId));
        String encoded = URLEncoder.encode(content.fileName() == null ? "image" : content.fileName(),
                StandardCharsets.UTF_8).replace("+", "%20");
        MediaType mediaType = content.mimeType() == null
                ? MediaType.APPLICATION_OCTET_STREAM
                : MediaType.parseMediaType(content.mimeType());
        return ResponseEntity.ok()
                .header("X-Content-Type-Options", "nosniff")
                // inline：让浏览器直接显示，而不是弹下载
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + encoded)
                // 私有聊天内容不进入浏览器或代理缓存。
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(mediaType)
                .body(content.payload());
    }
}
