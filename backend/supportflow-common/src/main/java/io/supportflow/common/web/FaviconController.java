package io.supportflow.common.web;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * 统一 favicon 出口。
 *
 * <p>浏览器访问后端地址时会自动请求 {@code /favicon.ico}，如果服务没有该静态资源，
 * Spring 会抛出 NoResourceFoundException 并污染错误日志。这里在公共模块统一返回
 * SupportFlow 图标，避免每个业务服务重复放置静态文件。</p>
 */
@RestController
public class FaviconController {

    private static final String SVG_TYPE = "image/svg+xml";
    private static final ClassPathResource FAVICON = new ClassPathResource("static/favicon.svg");

    @GetMapping(value = "/favicon.ico", produces = SVG_TYPE)
    public ResponseEntity<byte[]> favicon() throws IOException {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(SVG_TYPE))
                .body(FAVICON.getContentAsByteArray());
    }
}
