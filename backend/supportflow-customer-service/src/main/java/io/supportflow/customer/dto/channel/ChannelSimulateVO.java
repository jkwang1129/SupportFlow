package io.supportflow.customer.dto.channel;

import java.util.List;

/**
 * 原生渠道联调模拟器的结果：按渠道类型跑一遍真实进线链路，逐步给出结果。
 */
public record ChannelSimulateVO(int channelType, List<SimulateStepVO> steps, String note) {
}
