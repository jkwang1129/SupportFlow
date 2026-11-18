package io.supportflow.customer.service.tenant;

import java.util.List;
import io.supportflow.customer.dto.tenant.Step;

/**
 * 业务库脚本清单（独立库升级的"版本表"）。
 *
 * <p>三条约定：</p>
 * <ol>
 *   <li><b>只收增量脚本</b>：全量建库脚本 `customer_db.sql` 里的 `CREATE TABLE` 没有
 *       `IF NOT EXISTS`，在已有库上重跑会直接报"表已存在"，所以它是"基线"（版本 0），
 *       不进升级清单；清单里全是**幂等**的增量脚本；</li>
 *   <li><b>顺序与 `scripts/migrate-customer-db.sh` 保持一致</b>：共享库由那个脚本升级，
 *       独立库由本清单升级，两边顺序不一样就会出现"共享库有、独立库没有"的诡异差异；</li>
 *   <li><b>版本号只增不改</b>：已经发出去的版本号不允许改含义，只允许追加新版本。</li>
 * </ol>
 */
public final class TenantSchemaCatalog {

    /**
     * 版本 0 = 全量建库（`schema/customer_db.sql`）；1..N = 各增量脚本，按依赖顺序。
     */
    public static final List<Step> STEPS = List.of(
            new Step(1, "customer_db_security.sql", "访客接入安全加固（渠道域名白名单）"),
            new Step(2, "customer_db_collab.sql", "会话协同（内部备注 / 可见范围）"),
            new Step(3, "customer_db_delivery.sql", "消息必达（幂等键 / 会话内序号）"),
            new Step(4, "customer_db_qa.sql", "质检中心（任务 / 复核）"),
            new Step(5, "customer_db_routing.sql", "坐席状态与智能路由（技能组 / 排队）"),
            new Step(6, "customer_db_realtime_qa.sql", "会话实时质检（边聊边检）"),
            new Step(7, "customer_db_qa_source.sql", "质检送检来源（原文 + 多模态判读）"),
            new Step(8, "customer_db_qa_timeout.sql", "质检超时预警（响应超时秒数）"),
            new Step(9, "customer_db_kb.sql", "企业知识库（文档 / 切片 / 向量）"),
            new Step(10, "customer_db_bot_brain.sql", "AI 客服大脑（多轮对话状态）"),
            new Step(11, "customer_db_chat_image.sql", "多模态客服（聊天图片）"),
            new Step(12, "customer_db_ticket.sql", "工单系统（SLA / 流转 / 平台工单）"),
            new Step(13, "customer_db_customer360.sql", "客户 360（标签体系 / 客户动态）"),
            new Step(14, "customer_db_metrics.sql", "数据大屏与坐席绩效（按天预聚合）"),
            new Step(15, "customer_db_tenant_upgrade.sql", "租户库升级记录表"),
            new Step(16, "customer_db_rls.sql", "行级安全兜底策略（RLS）"),
            new Step(17, "customer_db_wechat.sql", "微信公众号原生接入（渠道配置 / openid 绑定 / 上行去重）"),
            new Step(18, "customer_db_native_channels.sql", "原生渠道接入（小程序 / App / 400 热线 / 邮件）"),
            new Step(19, "customer_db_phone_calls.sql", "400 热线通话记录（呼入 / 按键 / 接听 / 录音 / 挂机 / 建单）"),
            new Step(20, "customer_db_quick_reply.sql", "快捷话术（工作台 Alt+1~9，补使用次数列）"),
            // RLS 脚本按「扫描所有带 tenant_code 的表」工作，所以每加一批新表都要在最后重跑一次，
            // 否则新表（渠道配置 / 身份绑定 / 入站去重）会漏在兜底策略之外
            new Step(21, "customer_db_rls.sql", "行级安全兜底策略（补齐本批新增表）"),
            new Step(22, "customer_db_wechat_subscribe.sql",
                    "小程序订阅消息（订阅模板 ID + 发送日志，幂等靠部分唯一索引）"),
            // 又加了一张表（subscribe_message_log），RLS 兜底策略要再扫一遍
            new Step(23, "customer_db_rls.sql", "行级安全兜底策略（补齐小程序订阅消息表）"),
            // 400 热线语音转写：给已有的 channel_call_record 补 asr_* 列（不新增表，不用重跑 RLS）
            new Step(24, "customer_db_speech.sql", "400 热线语音转写（阿里云 NLS ASR 结果落库）"),
            // 同一企业同一种渠道只能有一个生效中：只加部分唯一索引，不新增表，不用重跑 RLS
            new Step(25, "customer_db_channel_unique.sql", "渠道唯一生效约束（同类型只能一个生效中）"),
            // 客户来源渠道列加宽：渠道名最长 64（邮件渠道名常常就是邮箱地址）
            new Step(26, "customer_db_customer_channel.sql", "客户来源渠道列加宽（VARCHAR(20)→VARCHAR(64)）"),
            // 会话来源列加宽：同一个根因（session.source 写的也是渠道名）
            new Step(27, "customer_db_session_source.sql", "会话来源列加宽（VARCHAR(20)→VARCHAR(64)）"),
            // 约定：清单最后一步永远是 RLS 兜底——以后往上面追加任何脚本（尤其是建表的），
            // 都要在末尾再补一个 RLS，让新表的行级安全策略跟着覆盖上（脚本本身幂等）。
            new Step(28, "customer_db_rls.sql", "行级安全兜底策略（补齐本批新增表）"),
            new Step(29, "customer_db_session_message_client_no.sql",
                    "会话消息幂等键加宽（邮件把 Message-ID 当 client_msg_no，64 位不够）"),
            new Step(30, "customer_db_rls.sql", "行级安全兜底策略（清单末步：覆盖前面所有新增表）")
    );

    /** 最新版本号 */
    public static final int LATEST_VERSION = STEPS.stream()
            .mapToInt(Step::version)
            .max()
            .orElse(0);

    private TenantSchemaCatalog() {
    }

    /** 按版本号取脚本；不存在返回 null（调用方按"版本对不上"处理） */
    public static Step of(int version) {
        for (Step step : STEPS) {
            if (step.version() == version) {
                return step;
            }
        }
        return null;
    }
}
