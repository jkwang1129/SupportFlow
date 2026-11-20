package io.supportflow.customer.service.bot;

import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

/** Exact reply content fingerprint; no trimming, normalization or raw content in receipts. */
public record AgentReplyArguments(String tenant, UUID runId, UUID eventId, UUID snapshotId, String content) {
    public AgentReplyArguments {
        if (tenant == null || !tenant.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,15}") || tenant.equalsIgnoreCase("PLATFORM")
                || runId == null || eventId == null || snapshotId == null || content == null || content.isBlank()
                || content.length() > 8192 || content.indexOf('\0') >= 0) throw new IllegalArgumentException("Invalid reply arguments");
        try {
            if (StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(content)).remaining() > 8192)
                throw new IllegalArgumentException("Reply exceeds UTF-8 byte limit");
        } catch (java.nio.charset.CharacterCodingException invalid) { throw new IllegalArgumentException("Invalid reply text"); }
    }

    public String argumentsSha256() {
        return digest(String.join("\n", "supportflow-session-reply:v1", tenant, runId.toString(), eventId.toString(),
                snapshotId.toString(), digest(content)));
    }

    private static String digest(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.GeneralSecurityException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    @Override public String toString() { return "AgentReplyArguments[tenant=" + tenant + ",runId=" + runId + ",content=[redacted]]"; }
}
