package io.supportflow.customer.dto.channel;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * 呼叫中心事件的回执（7 种事件共用一个结构，用到的字段才出现）。
 *
 * <p>呼入给 {@code action=play + say + menu}；按键给 {@code action=play/transfer + digits + label}；
 * 挂机给 {@code summary + ticketNo}；未接给 {@code status=missed + reason}。</p>
 *
 * @param menu 语音导航菜单项，由租户配置决定（字段是 key / label / action），
 *             额外字段原样带出，所以这里保持 Map
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PhoneEventResultVO(Boolean duplicated, String action, String say,
                                 List<Map<String, Object>> menu, String digits, String label,
                                 String status, String reason, String summary, String ticketNo,
                                 String sessionNo, String agentName, String recordingUrl,
                                 String note) {

    /** 幂等命中：同一条呼叫事件重复推。 */
    public static PhoneEventResultVO duplicated(String note) {
        return new PhoneEventResultVO(true, null, null, null, null, null, null, null, null, null,
                null, null, null, note);
    }

    /** 呼入：让话务侧播报语音导航菜单。 */
    public static PhoneEventResultVO play(String say, List<Map<String, Object>> menu, String sessionNo) {
        return new PhoneEventResultVO(null, "play", say, menu, null, null, null, null, null, null,
                sessionNo, null, null, null);
    }

    /** 按键：继续播报，或（选了人工）转接坐席。 */
    public static PhoneEventResultVO dtmf(boolean toAgent, String say, String digits, String label,
                                          String sessionNo) {
        return new PhoneEventResultVO(null, toAgent ? "transfer" : "play", say, null, digits, label,
                null, null, null, null, sessionNo, null, null, null);
    }

    /** 人工接听：记下接听坐席。 */
    public static PhoneEventResultVO answered(String sessionNo, String agentName) {
        return new PhoneEventResultVO(null, "none", null, null, null, null, null, null, null, null,
                sessionNo, agentName == null ? "" : agentName, null, null);
    }

    /** 录音回传：转写异步做，这里只回地址。 */
    public static PhoneEventResultVO recording(String recordingUrl) {
        return new PhoneEventResultVO(null, "none", null, null, null, null, null, null, null, null,
                null, null, recordingUrl == null ? "" : recordingUrl, null);
    }

    /** 未接通（未接 / 客户放弃）：只留记录，不建单。 */
    public static PhoneEventResultVO missed(String reason, String sessionNo) {
        return new PhoneEventResultVO(null, "none", null, null, null, null, "missed", reason, null,
                null, sessionNo, null, null, null);
    }

    /** 挂机：自动小结 + 建单，回工单号。 */
    public static PhoneEventResultVO hangup(String summary, String ticketNo, String sessionNo) {
        return new PhoneEventResultVO(null, "none", null, null, null, null, null, null, summary,
                ticketNo == null ? "" : ticketNo, sessionNo, null, null, null);
    }
}
