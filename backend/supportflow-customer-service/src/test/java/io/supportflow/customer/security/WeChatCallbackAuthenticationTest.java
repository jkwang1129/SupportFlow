package io.supportflow.customer.security;

import io.supportflow.customer.controller.wechat.WeChatCallbackController;
import io.supportflow.customer.entity.WeChatChannelConfig;
import io.supportflow.customer.wechat.WeChatConfigService;
import io.supportflow.customer.wechat.WeChatCrypt;
import io.supportflow.customer.wechat.WeChatInboundService;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WeChatCallbackAuthenticationTest {
    @Test
    void miniAppControllerPassesPlainSignatureAndRejectsUnverifiedMessages() {
        var configs = mock(WeChatConfigService.class);
        var inbound = mock(WeChatInboundService.class);
        when(configs.byAppId("fixture-app")).thenReturn(WeChatChannelConfig.builder()
                .appId("fixture-app").token("fixture-token").platform(2).build());
        var service = new io.supportflow.customer.wechat.MiniAppCallbackService(configs, inbound);
        var controller = new io.supportflow.customer.controller.wechat.MiniAppCallbackController(service);
        String body = "{\"Content\":\"Hello\"}";
        controller.receive("fixture-app", null, null, "100", "nonce", body);
        controller.receive("fixture-app", "invalid", null, "100", "nonce", body);
        controller.receive("fixture-app", null, null, "100", "nonce", "{\"Encrypt\":\"fixture\"}");
        verifyNoInteractions(inbound);
        when(inbound.handleJson("fixture-app", body, false)).thenReturn("success");
        String signed = signature("fixture-token", "100", "nonce");
        assertEquals("success", controller.receive("fixture-app", signed, null, "100", "nonce", body));
        verify(inbound).handleJson("fixture-app", body, false);
    }

    private static String signature(String... parts) {
        Arrays.sort(parts);
        return WeChatCrypt.sha1Hex(String.join("", parts));
    }

    @Test
    void onlyValidatedPlainMessagesReachInboundHandler() {
        var configs = mock(WeChatConfigService.class);
        var inbound = mock(WeChatInboundService.class);
        var config = WeChatChannelConfig.builder().appId("fixture-app").token("fixture-token").build();
        when(configs.byAppId("fixture-app")).thenReturn(config);
        var controller = new WeChatCallbackController(configs, inbound);
        String body = "<xml><Content>Hello</Content></xml>";
        controller.receive("fixture-app", null, null, "100", "nonce", body);
        controller.receive("fixture-app", "invalid", null, "100", "nonce", body);
        controller.receive("fixture-app", null, null, "100", "nonce", "<xml><Encrypt>fixture</Encrypt></xml>");
        verifyNoInteractions(inbound);
        String signed = signature("fixture-token", "100", "nonce");
        when(inbound.handle("fixture-app", body, false)).thenReturn("success");
        assertEquals("success", controller.receive("fixture-app", signed, null, "100", "nonce", body));
        verify(inbound).handle("fixture-app", body, false);
    }

    @Test
    void encryptedAndPlainSignatureChecksRequireAllInputsAndConfiguredToken() {
        assertFalse(WeChatCrypt.msgSignatureMatches("token", "100", "nonce", "encrypted", null));
        assertFalse(WeChatCrypt.msgSignatureMatches("", "100", "nonce", "encrypted", "invalid"));
        assertFalse(WeChatCrypt.msgSignatureMatches("token", null, "nonce", "encrypted", "invalid"));
        assertTrue(WeChatCrypt.msgSignatureMatches("token", "100", "nonce", "encrypted",
                signature("token", "100", "nonce", "encrypted")));
        assertFalse(WeChatCrypt.checkSignature("", signature("", "100", "nonce"), "100", "nonce"));
        assertTrue(WeChatCrypt.checkSignature("token", signature("token", "100", "nonce"), "100", "nonce"));
    }
}
