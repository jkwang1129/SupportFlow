package io.supportflow.customer.dto.channel;

/** 呼叫事件载荷 */
public record PhoneEvent(String event, String callId, String from, String digits,
                         String intent, String action, String duration,
                         /** 录音地址（recording 事件回传） */
                         String recordingUrl,
                         /** 接听坐席（answered 事件回传，用于算"等了多久、谁接的"） */
                         String agentName,
                         /** 未接通原因（no_answer / abandoned 事件回传） */
                         String reason) {
}
