package io.supportflow.customer.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 400 热线通话记录：一条呼叫一行，话务侧每推一个事件就更新这一行。
 *
 * <p>幂等键是 {@code (tenant_code, call_id)}：话务厂商的超时重发不会造出两条记录，
 * 也不会重复建单（挂机事件里判 ticket_no 是否已有）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("channel_call_record")
public class ChannelCallRecord {

    /** 状态：1-呼入中、2-已转人工、3-已完成、4-未接通（未接/放弃） */
    public static final int STATUS_RINGING = 1;
    public static final int STATUS_TRANSFERRED = 2;
    public static final int STATUS_FINISHED = 3;
    public static final int STATUS_MISSED = 4;

    /** 转写状态：1-成功、2-失败/跳过 */
    public static final int ASR_STATUS_SUCCESS = 1;
    public static final int ASR_STATUS_FAILED = 2;

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String tenantCode;
    private Long channelId;
    /** 话务侧呼叫唯一号 */
    private String callId;
    /** 主叫号码 */
    private String caller;
    private String callerName;
    private String customerNo;
    private String sessionNo;
    /** 挂机后自动建的工单号 */
    private String ticketNo;
    private Integer status;
    /** IVR 按键路径，例如 "1 · 订单查询" */
    private String ivrPath;
    private String ivrLabel;
    private Integer durationSeconds;
    private String recordingUrl;
    private String agentName;
    /** 通话小结（挂机时按模板生成） */
    private String transcript;
    /** 通话录音的语音识别文本（阿里云 NLS 一句话识别，异步回填） */
    private String asrText;
    /** 转写状态：1-成功、2-失败/跳过（未配置语音能力 / 录音拉不到 / 音频过大） */
    private Integer asrStatus;
    /** 转写失败或跳过时的原因，成功时为 null */
    private String asrHint;
    private LocalDateTime asrTime;
    private Integer eventCount;
    private LocalDateTime firstEventTime;
    private LocalDateTime answerTime;
    private LocalDateTime hangupTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private String creator;
    private String editor;

    @TableField("is_deleted")
    private Boolean deleted;
}
