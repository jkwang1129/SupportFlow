package io.supportflow.customer.dto.ticket;

/**
 * 当前登录人的工单权限（前端据此显隐按钮，服务端仍会再校验一次）。
 *
 * @param roleCode     主角色编码（如 ADMIN / AGENT / QUALITY）
 * @param roleName     角色中文名，直接显示
 * @param canOperate   能不能建单与处理（只读角色为 false）
 * @param canManageSla 能不能改 SLA 规则 / 手动扫超时（仅企业管理员）
 * @param hint         一句话说明当前角色能干什么，页面标题旁直接展示
 */
public record AccessVO(String roleCode, String roleName, boolean canOperate, boolean canManageSla,
                       String hint) {
}
