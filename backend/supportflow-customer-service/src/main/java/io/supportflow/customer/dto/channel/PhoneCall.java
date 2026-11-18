package io.supportflow.customer.dto.channel;

/** 呼叫事件载荷 */
/** 话务侧回调载荷（字段都可空：不同事件只带自己那几个） */
public record PhoneCall(String event, String callId, String from, String digits,
                        String intent, String action, String duration,
                        /** recording 事件带：录音地址 */
                        String recordingUrl,
                        /** answered 事件带：接听的坐席名 */
                        String agentName,
                        /** no_answer / abandoned 事件带：未接通原因 */
                        String reason) {
}
