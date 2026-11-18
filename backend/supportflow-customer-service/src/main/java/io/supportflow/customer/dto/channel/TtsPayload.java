package io.supportflow.customer.dto.channel;

/** IVR 播报合成载荷：text 必填，voice / format 不传就用平台默认（知祥 / wav） */
public record TtsPayload(String text, String voice, String format) {
}
