package io.supportflow.common.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.UUID;

/** Immutable retry identity. Validation and normalization happen before hashing or execution. */
public record UsageBatch(String batchId, String tenantCode, LocalDate statDate, long sessions,
                         long messages, long aiCalls, int seats, BigDecimal storageGb) {
    public UsageBatch {
        if (batchId == null || !UUID.fromString(batchId).toString().equals(batchId))
            throw new IllegalArgumentException("A canonical batch UUID is required");
        if (tenantCode == null || !tenantCode.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,15}")
                || "PLATFORM".equalsIgnoreCase(tenantCode))
            throw new IllegalArgumentException("A business tenant is required");
        if (statDate == null || sessions < 0 || messages < 0 || aiCalls < 0 || seats < 0)
            throw new IllegalArgumentException("Fixed date and nonnegative usage are required");
        storageGb = (storageGb == null ? BigDecimal.ZERO : storageGb).setScale(2, RoundingMode.UNNECESSARY);
        if (storageGb.signum() < 0 || storageGb.compareTo(new BigDecimal("9999999999.99")) > 0)
            throw new IllegalArgumentException("Storage exceeds the metering range");
    }

    public String fingerprint() {
        String canonical = String.join("\n", "usage-batch:v1", batchId, tenantCode, statDate.toString(),
                Long.toString(sessions), Long.toString(messages), Long.toString(aiCalls),
                Integer.toString(seats), storageGb.toPlainString());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
