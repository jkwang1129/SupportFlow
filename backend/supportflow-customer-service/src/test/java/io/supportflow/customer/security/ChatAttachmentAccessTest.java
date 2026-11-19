package io.supportflow.customer.security;

import io.supportflow.common.auth.AuthState;
import io.supportflow.common.auth.LoginUser;
import io.supportflow.common.context.LoginUserContext;
import io.supportflow.common.exception.BizException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChatAttachmentAccessTest {
    @Test
    void httpReadsRequireIdentityAndReturnPrivateNonCacheableImage() throws Exception {
        var files = org.mockito.Mockito.mock(io.supportflow.customer.service.session.SessionAttachmentService.class);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new io.supportflow.customer.controller.session.SessionAttachmentController(files, null, access))
                .setControllerAdvice(new io.supportflow.common.exception.GlobalExceptionHandler()).build();
        var anonymous = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/customer/sessions/attachments/1").param("sessionNo", "S1"))
                .andReturn().getResponse();
        assertFalse(anonymous.getContentType().startsWith("image/"));
        org.mockito.Mockito.verifyNoInteractions(files);
        org.mockito.Mockito.when(files.load("TENANT_A", "S1", 1L)).thenAnswer(call -> {
            assertEquals("TENANT_A", io.supportflow.common.context.TenantContext.get());
            return new io.supportflow.customer.dto.session.FileContent("image.png", "image/png", new byte[]{1});
        });
        var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/customer/sessions/attachments/1").param("sessionNo", "S1")
                .header("X-Visitor-Token", tokens.createToken(7, "Visitor", "TENANT_A", "S1")))
                .andReturn().getResponse();
        assertEquals(200, response.getStatus());
        assertEquals("image/png", response.getContentType());
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertEquals("nosniff", response.getHeader("X-Content-Type-Options"));
        assertArrayEquals(new byte[]{1}, response.getContentAsByteArray());
    }

    private final VisitorTokenService tokens = new VisitorTokenService(
            "fixture-media-jwt-secret-000000000000000000000000000", 3600, 7200);
    private final ChatAttachmentAccess access = new ChatAttachmentAccess(tokens);

    @Test
    void onlySessionTokenOrEnterpriseIdentityCanAuthorizeMedia() throws Exception {
        String visitor = tokens.createToken(7, "Visitor", "TENANT_A", "S1");
        assertEquals("TENANT_A", access.tenant("S1", visitor));
        assertThrows(BizException.class, () -> access.tenant("S2", visitor));
        assertThrows(BizException.class, () -> access.tenant("S1", null));
        assertThrows(BizException.class, () -> access.tenant("S1",
                tokens.createIdentityToken(7, "C1", "Visitor", "TENANT_A")));
        LoginUserContext.bind(AuthState.ok(new LoginUser(1L, "agent", "Agent", 2, "TENANT_A")), () -> {
            assertEquals("TENANT_A", access.tenant("S1", null));
            return null;
        });
        LoginUserContext.bind(AuthState.ok(new LoginUser(1L, "admin", "Admin", 1, "PLATFORM")), () -> {
            assertThrows(BizException.class, () -> access.tenant("S1", null));
            return null;
        });
    }
}
