package io.supportflow.customer.service.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.supportflow.common.exception.BizException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

/**
 * PII-redacted visitor customer profile projection bound to the current source snapshot.
 * Never returns raw phone, internal remark, or enterprise staff fields.
 */
public final class AgentCustomerQueries {
    public record Profile(
            String tenant,
            UUID eventId,
            UUID runId,
            UUID snapshotId,
            String sourceHash,
            boolean bound,
            String customerNo,
            String maskedName,
            String maskedPhone,
            Integer level,
            Integer customerType,
            Integer points,
            Integer ordersCount,
            Integer ticketCount,
            Integer sessionCount,
            Integer riskLevel,
            String resourceVersion) { }

    private record CustomerRow(
            String customerNo,
            String name,
            String phone,
            Integer level,
            Integer customerType,
            Integer points,
            Integer ordersCount,
            Integer ticketCount,
            Integer sessionCount,
            Integer riskLevel) { }

    private final JdbcTemplate jdbc;
    private final AgentSourceSnapshots snapshots;
    private final ObjectMapper mapper = new ObjectMapper();

    public AgentCustomerQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.snapshots = new AgentSourceSnapshots(jdbc);
    }

    public Profile read(String tenant, UUID eventId, UUID runId) {
        var source = snapshots.capture(tenant, eventId, runId);
        String customerRef = extractCustomerRef(source.payloadJson());
        if (customerRef == null) {
            String canonical = canonicalDigestInput(
                    tenant, eventId, runId, source.snapshotId(), source.payloadHash(),
                    false, null, null, null, null, null, null, null, null, null, null);
            return new Profile(
                    tenant, eventId, runId, source.snapshotId(), source.payloadHash(),
                    false, null, null, null, null, null, null, null, null, null, null,
                    digest(canonical));
        }

        Long customerId = parseCustomerId(customerRef);
        var rows = jdbc.query("""
                SELECT customer_no, name, phone,
                       coalesce(level, 1) AS level,
                       coalesce(customer_type, 1) AS customer_type,
                       coalesce(points, 0) AS points,
                       coalesce(orders_count, 0) AS orders_count,
                       coalesce(ticket_count, 0) AS ticket_count,
                       coalesce(session_count, 0) AS session_count,
                       coalesce(risk_level, 1) AS risk_level
                FROM customer
                WHERE tenant_code = ? AND id = ? AND is_deleted = false
                  AND anonymized_at IS NULL AND merged_into IS NULL
                FOR SHARE
                """,
                (row, index) -> new CustomerRow(
                        row.getString("customer_no"),
                        row.getString("name"),
                        row.getString("phone"),
                        (Integer) row.getObject("level"),
                        (Integer) row.getObject("customer_type"),
                        (Integer) row.getObject("points"),
                        (Integer) row.getObject("orders_count"),
                        (Integer) row.getObject("ticket_count"),
                        (Integer) row.getObject("session_count"),
                        (Integer) row.getObject("risk_level")),
                tenant, customerId);
        if (rows.size() != 1) {
            throw unavailable();
        }

        CustomerRow customer = rows.getFirst();
        validateRow(customer);
        String maskedName = maskName(customer.name());
        String maskedPhone = maskPhone(customer.phone());
        String canonical = canonicalDigestInput(
                tenant, eventId, runId, source.snapshotId(), source.payloadHash(),
                true, customer.customerNo(), maskedName, maskedPhone,
                customer.level(), customer.customerType(), customer.points(),
                customer.ordersCount(), customer.ticketCount(), customer.sessionCount(),
                customer.riskLevel());
        return new Profile(
                tenant, eventId, runId, source.snapshotId(), source.payloadHash(),
                true, customer.customerNo(), maskedName, maskedPhone,
                customer.level(), customer.customerType(), customer.points(),
                customer.ordersCount(), customer.ticketCount(), customer.sessionCount(),
                customer.riskLevel(), digest(canonical));
    }

    private String extractCustomerRef(String payloadJson) {
        try {
            var field = mapper.readTree(payloadJson).path("trusted_source").path("customer_ref");
            if (!field.isNull() && !field.isTextual()) {
                throw new IllegalStateException("Invalid trusted customer binding");
            }
            return field.isNull() ? null : field.textValue();
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IllegalStateException("Invalid trusted source snapshot", invalid);
        }
    }

    private static Long parseCustomerId(String customerRef) {
        try {
            if (!customerRef.matches("[1-9][0-9]{0,18}")) {
                throw new NumberFormatException();
            }
            return Long.valueOf(customerRef);
        } catch (NumberFormatException invalid) {
            throw unavailable();
        }
    }

    private static void validateRow(CustomerRow row) {
        if (row.customerNo() == null || !row.customerNo().matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")
                || row.level() == null || row.level() < 1 || row.level() > 5
                || row.customerType() == null || row.customerType() < 1 || row.customerType() > 2
                || row.riskLevel() == null || row.riskLevel() < 1 || row.riskLevel() > 3
                || row.points() == null || row.points() < 0
                || row.ordersCount() == null || row.ordersCount() < 0
                || row.ticketCount() == null || row.ticketCount() < 0
                || row.sessionCount() == null || row.sessionCount() < 0) {
            throw new BizException(42207, "客户档案状态需要核验");
        }
    }

    static String maskName(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.strip();
        if (trimmed.isEmpty() || trimmed.indexOf('\0') >= 0) {
            return null;
        }
        int firstCodePoint = trimmed.codePointAt(0);
        return new String(Character.toChars(firstCodePoint)) + "*";
    }

    static String maskPhone(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.strip();
        if (trimmed.isEmpty() || trimmed.indexOf('\0') >= 0) {
            return null;
        }
        StringBuilder digitsBuilder = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (ch >= '0' && ch <= '9') {
                digitsBuilder.append(ch);
            }
        }
        String digits = digitsBuilder.toString();
        if (digits.length() == 13 && digits.startsWith("86")) {
            digits = digits.substring(2);
        }
        if (digits.length() >= 7) {
            return digits.substring(0, 3) + "****" + digits.substring(digits.length() - 4);
        }
        return "****";
    }

    private static String canonicalDigestInput(
            String tenant,
            UUID eventId,
            UUID runId,
            UUID snapshotId,
            String sourceHash,
            boolean bound,
            String customerNo,
            String maskedName,
            String maskedPhone,
            Integer level,
            Integer customerType,
            Integer points,
            Integer ordersCount,
            Integer ticketCount,
            Integer sessionCount,
            Integer riskLevel) {
        return String.join("\n",
                "supportflow-customer-profile:v1",
                tenant,
                eventId.toString(),
                runId.toString(),
                snapshotId.toString(),
                sourceHash,
                Boolean.toString(bound),
                customerNo == null ? "" : customerNo,
                maskedName == null ? "" : maskedName,
                maskedPhone == null ? "" : maskedPhone,
                level == null ? "" : Integer.toString(level),
                customerType == null ? "" : Integer.toString(customerType),
                points == null ? "" : Integer.toString(points),
                ordersCount == null ? "" : Integer.toString(ordersCount),
                ticketCount == null ? "" : Integer.toString(ticketCount),
                sessionCount == null ? "" : Integer.toString(sessionCount),
                riskLevel == null ? "" : Integer.toString(riskLevel));
    }

    private static BizException unavailable() {
        return new BizException(40407, "可访问的客户档案不存在");
    }

    private static String digest(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException impossible) {
            throw new IllegalStateException("Customer profile digest unavailable", impossible);
        }
    }
}
