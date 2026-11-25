package io.supportflow.customer.storage;

import io.supportflow.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class StorageExceptionHandler {
    @ExceptionHandler(StorageUnavailableException.class)
    public ResponseEntity<ApiResponse<Void>> unavailable(StorageUnavailableException error, HttpServletRequest request) {
        var response = ResponseEntity.status(503).header(HttpHeaders.CACHE_CONTROL, "no-store");
        if ("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod())) {
            response.header(HttpHeaders.RETRY_AFTER, "5");
        }
        return response.body(ApiResponse.fail(error.getCode(), error.getMessage()));
    }
}
