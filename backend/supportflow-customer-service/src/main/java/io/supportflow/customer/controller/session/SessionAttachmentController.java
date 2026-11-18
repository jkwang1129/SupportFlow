package io.supportflow.customer.controller.session;

import io.supportflow.common.api.ApiResponse;
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
 *   <li><b>上传</b>：访客没有登录态，所以用**渠道密钥**（appKey）证明"这条会话来自我的渠道"。
 *       租户由密钥解析出来，不信前端传的任何租户参数——这是多租户系统的底线；</li>
 *   <li><b>读取</b>：聊天图片要同时给访客和坐席看，所以不能要求登录态，
 *       靠不可猜的雪花 fileId 当凭据。生产环境应换成带时效的签名 URL（这里留了注释说明）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/customer/sessions/attachments")
public class SessionAttachmentController {

    private final SessionAttachmentService attachmentService;

    public SessionAttachmentController(SessionAttachmentService attachmentService) {
        this.attachmentService = attachmentService;
    }

    /**
     * 访客上传一张聊天图片（聊天窗口里点「发送图片」）。
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<UploadResult> upload(
            @RequestParam("appKey") String appKey,
            @RequestParam("sessionNo") String sessionNo,
            @RequestPart("file") MultipartFile file
    ) {
        return ApiResponse.ok(attachmentService.upload(attachmentService.tenantOf(appKey), sessionNo, file));
    }

    /**
     * 按文件 ID 读取图片（消息气泡里直接当 img src 用）。
     */
    @GetMapping("/{fileId}")
    public ResponseEntity<byte[]> load(@PathVariable long fileId) throws IOException {
        FileContent content = attachmentService.load(fileId);
        String encoded = URLEncoder.encode(content.fileName() == null ? "image" : content.fileName(),
                StandardCharsets.UTF_8).replace("+", "%20");
        MediaType mediaType = content.mimeType() == null
                ? MediaType.APPLICATION_OCTET_STREAM
                : MediaType.parseMediaType(content.mimeType());
        return ResponseEntity.ok()
                // inline：让浏览器直接显示，而不是弹下载
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + encoded)
                // 图片按 fileId 不可变，缓存一天，消息列表反复渲染时不再回源
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=86400")
                .contentType(mediaType)
                .body(content.payload());
    }
}
