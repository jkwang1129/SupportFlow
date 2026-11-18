package io.supportflow.customer.service.phone;

import io.supportflow.common.exception.BizException;
import io.supportflow.common.id.SnowflakeIdGenerator;
import io.supportflow.customer.dto.channel.CallPageVO;
import io.supportflow.customer.dto.channel.CallRowVO;
import io.supportflow.customer.dto.channel.CallSummaryVO;
import io.supportflow.customer.entity.Channel;
import io.supportflow.customer.entity.ChannelCallRecord;
import io.supportflow.customer.mapper.ChannelCallRecordMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 400 热线通话记录：把话务侧推来的事件叠成一条可查、可复盘的呼叫记录。
 *
 * <p>为什么要单独一张表：呼入 / 按键 / 接听 / 挂机以前只写进会话消息流，
 * 运营没法回答"今天来了几通电话、多少转人工、平均通话多久、录音在哪、结成哪张工单"。
 * 这里一条呼叫一行（租户 + call_id 唯一），事件往上叠，挂机时补齐时长、录音与小结。</p>
 *
 * <p><b>幂等</b>：话务厂商的超时重发很常见，所以这里全部按 call_id upsert——
 * 重复事件只刷新字段与事件计数，不会造出第二条记录（去重仍在 channel_inbound_log 那层做）。</p>
 */
@Service
public class PhoneCallService {

    private static final Logger log = LoggerFactory.getLogger(PhoneCallService.class);

    /** IVR 路径最多记这么长（长按键序列没必要全存） */
    private static final int IVR_PATH_MAX = 240;

    private final ChannelCallRecordMapper callMapper;
    private final SnowflakeIdGenerator idGenerator;

    public PhoneCallService(ChannelCallRecordMapper callMapper, SnowflakeIdGenerator idGenerator) {
        this.callMapper = callMapper;
        this.idGenerator = idGenerator;
    }

    /** 呼入：没有记录就建一条（状态=呼入中），已有就补上会话与客户信息 */
    @Transactional
    public ChannelCallRecord start(Channel channel, String callId, String caller,
                                   String callerName, String sessionNo, String customerNo) {
        ChannelCallRecord current = find(channel, callId);
        LocalDateTime now = LocalDateTime.now();
        if (current == null) {
            ChannelCallRecord row = ChannelCallRecord.builder()
                    .id(idGenerator.nextId())
                    .tenantCode(channel.getTenantCode())
                    .channelId(channel.getId())
                    .callId(callId)
                    .caller(caller)
                    .callerName(callerName)
                    .customerNo(customerNo)
                    .sessionNo(sessionNo)
                    .status(ChannelCallRecord.STATUS_RINGING)
                    .eventCount(1)
                    .firstEventTime(now)
                    .createTime(now)
                    .updateTime(now)
                    .creator("PHONE_WEBHOOK")
                    .deleted(false)
                    .build();
            callMapper.insert(row);
            log.info("400 通话记录已建 tenant={} callId={} caller={}", channel.getTenantCode(), callId, caller);
            return row;
        }
        update(channel, callId, wrapper -> wrapper
                .set(current.getSessionNo() == null && sessionNo != null, ChannelCallRecord::getSessionNo, sessionNo)
                .set(current.getCustomerNo() == null && customerNo != null,
                        ChannelCallRecord::getCustomerNo, customerNo)
                .set(current.getCallerName() == null && callerName != null,
                        ChannelCallRecord::getCallerName, callerName)
                .set(ChannelCallRecord::getEventCount, intOf(current.getEventCount()) + 1));
        return find(channel, callId);
    }

    /** 记一次 IVR 按键（路径与最后一个标签） */
    @Transactional
    public void appendIvr(Channel channel, String callId, String digits, String label) {
        ChannelCallRecord current = find(channel, callId);
        if (current == null) {
            return;
        }
        String step = (digits == null || digits.isBlank() ? "?" : digits)
                + (label == null || label.isBlank() ? "" : " · " + label);
        String path = current.getIvrPath() == null || current.getIvrPath().isBlank()
                ? step
                : current.getIvrPath() + " → " + step;
        if (path.length() > IVR_PATH_MAX) {
            path = path.substring(path.length() - IVR_PATH_MAX);
        }
        String finalPath = path;
        update(channel, callId, wrapper -> wrapper
                .set(ChannelCallRecord::getIvrPath, finalPath)
                .set(ChannelCallRecord::getIvrLabel, label)
                .set(ChannelCallRecord::getEventCount, intOf(current.getEventCount()) + 1));
    }

    /** 转人工：状态置为已转人工，记首个接听时间 */
    @Transactional
    public void markTransferred(Channel channel, String callId, String sessionNo, String agentName) {
        ChannelCallRecord current = find(channel, callId);
        if (current == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        update(channel, callId, wrapper -> wrapper
                .set(ChannelCallRecord::getStatus, ChannelCallRecord.STATUS_TRANSFERRED)
                .set(sessionNo != null, ChannelCallRecord::getSessionNo, sessionNo)
                .set(agentName != null && !agentName.isBlank(), ChannelCallRecord::getAgentName, agentName)
                .set(current.getAnswerTime() == null, ChannelCallRecord::getAnswerTime, now)
                .set(ChannelCallRecord::getEventCount, intOf(current.getEventCount()) + 1));
    }

    /** 录音回传（话务侧一条录音通常比挂机事件晚几秒到几十秒） */
    @Transactional
    public void markRecording(Channel channel, String callId, String recordingUrl) {
        ChannelCallRecord current = find(channel, callId);
        if (current == null || recordingUrl == null || recordingUrl.isBlank()) {
            return;
        }
        update(channel, callId, wrapper -> wrapper
                .set(ChannelCallRecord::getRecordingUrl, recordingUrl)
                .set(ChannelCallRecord::getEventCount, intOf(current.getEventCount()) + 1));
    }

    /** 未接通（未接 / 客户放弃 / 线路异常）：不建单，只留记录与原因 */
    @Transactional
    public void markMissed(Channel channel, String callId, String reason) {
        ChannelCallRecord current = find(channel, callId);
        if (current == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        String text = reason == null || reason.isBlank() ? "未接通" : reason;
        update(channel, callId, wrapper -> wrapper
                .set(ChannelCallRecord::getStatus, ChannelCallRecord.STATUS_MISSED)
                .set(ChannelCallRecord::getTranscript, text)
                .set(ChannelCallRecord::getHangupTime, now)
                .set(ChannelCallRecord::getEventCount, intOf(current.getEventCount()) + 1));
    }

    /**
     * 回填语音识别结果（录音回传后异步做，不参与事件计数）。
     *
     * <p>失败也写：``asr_status=2`` + ``asr_hint`` 说明为什么没转写，
     * 页面据此显示"未配置语音能力 / 录音拉不到"，而不是留一片空白让人猜。</p>
     */
    @Transactional
    public void markAsr(Channel channel, String callId, boolean success, String text, String hint) {
        ChannelCallRecord current = find(channel, callId);
        if (current == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        boolean hasText = success && text != null && !text.isBlank();
        String safeText = hasText ? truncate(text.trim(), 2000) : null;
        String safeHint = hasText ? null : truncate(
                hint == null || hint.isBlank() ? "语音识别没有返回内容" : hint.trim(), 250);
        update(channel, callId, wrapper -> wrapper
                .set(hasText, ChannelCallRecord::getAsrText, safeText)
                .set(ChannelCallRecord::getAsrStatus, hasText
                        ? ChannelCallRecord.ASR_STATUS_SUCCESS : ChannelCallRecord.ASR_STATUS_FAILED)
                .set(ChannelCallRecord::getAsrHint, safeHint)
                .set(ChannelCallRecord::getAsrTime, now));
        log.info("400 通话转写回填 tenant={} callId={} 成功={} 文本={}字 说明={}",
                channel.getTenantCode(), callId, hasText,
                hasText ? safeText.length() : 0, safeHint == null ? "-" : safeHint);
    }

    /** 挂机：补时长、小结、工单号，状态置为已完成 */
    @Transactional
    public void finish(Channel channel, String callId, Integer durationSeconds,
                       String transcript, String ticketNo, String sessionNo) {
        ChannelCallRecord current = find(channel, callId);
        if (current == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        update(channel, callId, wrapper -> wrapper
                .set(ChannelCallRecord::getStatus, ChannelCallRecord.STATUS_FINISHED)
                .set(durationSeconds != null, ChannelCallRecord::getDurationSeconds, durationSeconds)
                .set(transcript != null && !transcript.isBlank(),
                        ChannelCallRecord::getTranscript, transcript)
                .set(ticketNo != null && !ticketNo.isBlank(), ChannelCallRecord::getTicketNo, ticketNo)
                .set(sessionNo != null, ChannelCallRecord::getSessionNo, sessionNo)
                .set(ChannelCallRecord::getHangupTime, now)
                .set(ChannelCallRecord::getEventCount, intOf(current.getEventCount()) + 1));
    }

    /** 通话记录列表（分页 + 今日概况），企业侧只在电话渠道详情里查自己租户的 */
    public CallPageVO page(String tenantCode, Long channelId, Integer status,
                           String keyword, int page, int pageSize) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, pageSize), 100);
        String kw = keyword == null || keyword.isBlank() ? null : keyword.trim();
        long total = callMapper.countCallPage(tenantCode, channelId, status, kw);
        List<Map<String, Object>> rows = callMapper.selectCallPage(tenantCode, channelId, status, kw,
                safeSize, (long) (safePage - 1) * safeSize);
        List<CallRowVO> list = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            list.add(new CallRowVO(str(row.get("id")), str(row.get("callId")), str(row.get("caller")),
                    str(row.get("callerName")), str(row.get("customerNo")), str(row.get("sessionNo")),
                    str(row.get("ticketNo")), intOrNull(row.get("status")), str(row.get("ivrPath")),
                    str(row.get("ivrLabel")), intOrNull(row.get("durationSeconds")),
                    str(row.get("recordingUrl")), str(row.get("agentName")), str(row.get("transcript")),
                    str(row.get("asrText")), str(row.get("asrStatus")), str(row.get("asrHint")),
                    localDateTimeOrNull(row.get("asrTime")), intOrNull(row.get("eventCount")),
                    localDateTimeOrNull(row.get("firstEventTime")),
                    localDateTimeOrNull(row.get("answerTime")),
                    localDateTimeOrNull(row.get("hangupTime")),
                    localDateTimeOrNull(row.get("createTime"))));
        }
        Map<String, Object> raw = callMapper.selectCallSummary(tenantCode, channelId);
        CallSummaryVO summary = new CallSummaryVO(
                longOrNull(raw == null ? null : raw.get("total")),
                longOrNull(raw == null ? null : raw.get("finished")),
                longOrNull(raw == null ? null : raw.get("transferred")),
                longOrNull(raw == null ? null : raw.get("missed")),
                decimalOrNull(raw == null ? null : raw.get("avgDuration")));
        return new CallPageVO(total, safePage, safeSize, summary, list);
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Integer intOrNull(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static Long longOrNull(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static BigDecimal decimalOrNull(Object value) {
        return value instanceof Number number ? BigDecimal.valueOf(number.doubleValue()) : null;
    }

    /** JDBC 的 TIMESTAMP 可能是 java.sql.Timestamp / LocalDateTime / 字符串，统一成 LocalDateTime */
    private static LocalDateTime localDateTimeOrNull(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime time) {
            return time;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        return LocalDateTime.parse(String.valueOf(value).replace(' ', 'T'));
    }

    /** 通话记录详情（页面上点开某一条看完整时间线） */
    public ChannelCallRecord detail(String tenantCode, String callId) {
        ChannelCallRecord row = callMapper.selectOne(Wrappers.<ChannelCallRecord>lambdaQuery()
                .eq(ChannelCallRecord::getTenantCode, tenantCode)
                .eq(ChannelCallRecord::getCallId, callId)
                .eq(ChannelCallRecord::getDeleted, false)
                .last("LIMIT 1"));
        if (row == null) {
            throw new BizException(40401, "通话记录不存在：" + callId);
        }
        return row;
    }

    /** 按呼叫号查记录（可能为 null）：挂机事件用它判断"这条通话是不是已经建过单" */
    public ChannelCallRecord detailOf(String tenantCode, String callId) {
        if (tenantCode == null || callId == null || callId.isBlank()) {
            return null;
        }
        return callMapper.selectOne(Wrappers.<ChannelCallRecord>lambdaQuery()
                .eq(ChannelCallRecord::getTenantCode, tenantCode)
                .eq(ChannelCallRecord::getCallId, callId)
                .eq(ChannelCallRecord::getDeleted, false)
                .last("LIMIT 1"));
    }

    private ChannelCallRecord find(Channel channel, String callId) {
        return callMapper.selectOne(Wrappers.<ChannelCallRecord>lambdaQuery()
                .eq(ChannelCallRecord::getTenantCode, channel.getTenantCode())
                .eq(ChannelCallRecord::getCallId, callId)
                .eq(ChannelCallRecord::getDeleted, false)
                .last("LIMIT 1"));
    }

    private void update(Channel channel, String callId,
                        Consumer<LambdaUpdateWrapper<ChannelCallRecord>> customizer) {
        LambdaUpdateWrapper<ChannelCallRecord> wrapper = Wrappers.lambdaUpdate(ChannelCallRecord.class)
                .eq(ChannelCallRecord::getTenantCode, channel.getTenantCode())
                .eq(ChannelCallRecord::getCallId, callId)
                .set(ChannelCallRecord::getUpdateTime, LocalDateTime.now());
        customizer.accept(wrapper);
        callMapper.update(null, wrapper);
    }

    private static int intOf(Integer value) {
        return value == null ? 0 : value;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
