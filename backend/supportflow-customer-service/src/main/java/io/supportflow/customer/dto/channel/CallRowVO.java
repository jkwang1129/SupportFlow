package io.supportflow.customer.dto.channel;

import java.time.LocalDateTime;

/**
 * 400 通话记录的一行。
 */
public record CallRowVO(String id, String callId, String caller, String callerName,
                        String customerNo, String sessionNo, String ticketNo, Integer status,
                        String ivrPath, String ivrLabel, Integer durationSeconds,
                        String recordingUrl, String agentName, String transcript,
                        String asrText, String asrStatus, String asrHint, LocalDateTime asrTime,
                        Integer eventCount, LocalDateTime firstEventTime,
                        LocalDateTime answerTime, LocalDateTime hangupTime,
                        LocalDateTime createTime) {
}
