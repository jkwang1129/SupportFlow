package io.supportflow.customer.controller.platform;

import io.supportflow.common.context.LoginUserContext;
import io.supportflow.common.api.ApiResponse;
import io.supportflow.common.auth.LoginUser;
import io.supportflow.customer.dto.license.FileContent;
import io.supportflow.customer.dto.license.UploadResult;
import io.supportflow.customer.service.license.EnterpriseLicenseFileService;
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

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 企业营业执照附件接口：上传到对象存储、下载/预览。
 */
@RestController
@RequestMapping("/api/customer/files/enterprise")
public class EnterpriseLicenseFileController {

    private final EnterpriseLicenseFileService fileService;

    public EnterpriseLicenseFileController(
            EnterpriseLicenseFileService fileService
    ) {
        this.fileService = fileService;
    }

    /**
     * 上传营业执照扫描件。
     */
    @PostMapping(value = "/license", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<UploadResult> uploadLicense(
            @RequestParam("file") MultipartFile file
    ) {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(fileService.uploadLicense(user, file));
    }

    /**
     * 按文件 ID 下载/预览营业执照。
     */
    @GetMapping("/{fileId}")
    public ResponseEntity<byte[]> downloadLicense(
            @PathVariable long fileId
    ) throws IOException {
        LoginUser user = LoginUserContext.require();
        FileContent content = fileService.loadLicense(user, fileId);
        String encoded = URLEncoder.encode(content.fileName(), StandardCharsets.UTF_8).replace("+", "%20");
        MediaType mediaType = content.mimeType() == null
                ? MediaType.APPLICATION_OCTET_STREAM
                : MediaType.parseMediaType(content.mimeType());
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + encoded)
                .contentType(mediaType)
                .body(content.inputStream().readAllBytes());
    }
}
