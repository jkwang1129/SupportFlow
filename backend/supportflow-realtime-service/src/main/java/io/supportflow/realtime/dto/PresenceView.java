package io.supportflow.realtime.dto;

import java.util.List;

/**
 * 在线快照。
 */
public record PresenceView(List<String> onlineSessions) {
}
