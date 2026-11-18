package io.supportflow.customer.dto.session;

import java.util.List;

/** 我的 + 同事的 */
public record StatusView(AgentStatusVO mine, List<AgentStatusVO> agents) {
}
