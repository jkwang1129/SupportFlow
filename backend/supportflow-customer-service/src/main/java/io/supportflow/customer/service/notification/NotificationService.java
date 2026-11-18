package io.supportflow.customer.service.notification;

import io.supportflow.common.auth.LoginUser;
import io.supportflow.common.exception.BizException;
import io.supportflow.common.id.SnowflakeIdGenerator;
import io.supportflow.customer.dto.notification.NotificationCountVO;
import io.supportflow.customer.dto.notification.NotificationPageVO;
import io.supportflow.customer.dto.notification.NotificationSummaryVO;
import io.supportflow.customer.dto.notification.NotificationTypeCountVO;
import io.supportflow.customer.dto.notification.NotificationVO;
import io.supportflow.customer.entity.Notification;
import io.supportflow.customer.entity.NotificationRead;
import io.supportflow.customer.mapper.NotificationMapper;
import io.supportflow.customer.mapper.NotificationReadMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 消息中心：把"系统 / 工单 / 审核 / 质检"各类提醒收进一个地方。
 *
 * <p>设计要点：</p>
 * <ol>
 *   <li><b>消息存租户、已读存人</b>：一条"3 个工单即将超时"是发给整个企业的，
 *       不该给每个坐席各存一份；谁读过谁写 {@code notification_read}（唯一索引挡重复）；</li>
 *   <li><b>写消息不许影响业务</b>：发布失败只记日志——工单该流转还得流转，
 *       不能因为"提醒没写进去"把主流程带崩；</li>
 *   <li><b>跳转用页面标识</b>：存 {@code link_view}（如 tickets / platform-support），
 *       路由是前端的事，后端不该知道 URL。</li>
 * </ol>
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    /** 类型码：1-系统、2-工单、3-审核、4-质检、5-公告、6-账单 */
    public static final int TYPE_SYSTEM = 1;
    public static final int TYPE_TICKET = 2;
    public static final int TYPE_AUDIT = 3;
    public static final int TYPE_QA = 4;

    /** 目标：1-用户、2-企业 */
    private static final int TARGET_USER = 1;
    private static final int TARGET_TENANT = 2;

    private static final int LIST_LIMIT = 100;

    /** 消息中心页：一页最多 100 条（再多也不适合用列表看） */
    private static final int PAGE_MAX = 100;

    /** 未读筛选 / 分类统计的扫描上限：已读按人存，SQL 里筛不掉，按最近这些条算 */
    private static final int SCAN_LIMIT = 500;

    /** 分类统计页签的顺序：工单、公告排前面，剩下的按类型码 */
    private static final int[] SUMMARY_TYPES = {TYPE_TICKET, 5, TYPE_SYSTEM, TYPE_AUDIT, TYPE_QA, 6};

    private final NotificationMapper notificationMapper;
    private final NotificationReadMapper notificationReadMapper;
    private final SnowflakeIdGenerator idGenerator;

    public NotificationService(NotificationMapper notificationMapper,
                               NotificationReadMapper notificationReadMapper,
                               SnowflakeIdGenerator idGenerator) {
        this.notificationMapper = notificationMapper;
        this.notificationReadMapper = notificationReadMapper;
        this.idGenerator = idGenerator;
    }

    /**
     * 发一条给整个企业的消息（工单 SLA 预警、平台回复这类）。
     */
    public void publishToTenant(String tenantCode, int notifyType, String title, String content, String linkView) {
        publish(tenantCode, notifyType, title, content, linkView, null);
    }

    /**
     * 发一条给具体某个人的消息（"这个工单分给你了"）。
     *
     * <p>userId 为空时退化成企业级消息：宁可发宽一点，也别让提醒丢掉。</p>
     */
    public void publishToUser(String tenantCode, long userId, int notifyType, String title, String content,
                              String linkView) {
        publish(tenantCode, notifyType, title, content, linkView, userId);
    }

    private void publish(String tenantCode, int notifyType, String title, String content, String linkView,
                         Long userId) {
        if (tenantCode == null || tenantCode.isBlank()) {
            return;
        }
        try {
            LocalDateTime now = LocalDateTime.now();
            notificationMapper.insert(Notification.builder()
                    .id(idGenerator.nextId())
                    .tenantCode(tenantCode)
                    .notifyType(notifyType)
                    .title(limit(title, 128))
                    .content(content)
                    .linkView(linkView)
                    .targetType(userId == null ? TARGET_TENANT : TARGET_USER)
                    .targetId(userId)
                    .publishTime(now)
                    .createTime(now)
                    .build());
        } catch (Exception e) {
            // 提醒写不进去不影响工单流转本身，但要留痕（否则"为什么没收到提醒"没法查）
            log.warn("写消息中心失败 tenant={} type={} title={} error={}",
                    tenantCode, notifyType, title, e.getMessage());
        }
    }

    /** 消息列表：企业级 + 发给我的，带已读状态 */
    public List<NotificationVO> list(LoginUser user, Integer notifyType, Integer limit) {
        String tenant = tenantOf(user);
        int size = limit == null || limit < 1 ? 50 : Math.min(limit, LIST_LIMIT);
        List<Notification> rows = notificationMapper.selectList(visible(user, notifyType, null)
                .orderByDesc(Notification::getPublishTime)
                .last("LIMIT " + size));
        return toVOs(user, rows, tenant);
    }

    /**
     * 消息中心页（带分页）：关键字搜标题与正文，可选只看未读。
     *
     * <p>总数用 {@code selectCount} 精确算；「只看未读」因为已读是按人存的、SQL 里表达不了，
     * 退化成"在最近 {@value #SCAN_LIMIT} 条里挑未读"——消息中心本来就是看最近的东西，
     * 这个口径写在返回里（{@code scanLimit}），前端能照实说清楚。</p>
     */
    public NotificationPageVO page(LoginUser user, Integer notifyType, Integer page, Integer pageSize,
                                   String keyword, Boolean unreadOnly) {
        String tenant = tenantOf(user);
        int current = page == null || page < 1 ? 1 : page;
        int size = pageSize == null || pageSize < 1 ? 10 : Math.min(pageSize, PAGE_MAX);
        if (Boolean.TRUE.equals(unreadOnly)) {
            List<NotificationVO> unread = toVOs(user, notificationMapper.selectList(
                            visible(user, notifyType, keyword)
                                    .orderByDesc(Notification::getPublishTime)
                                    .last("LIMIT " + SCAN_LIMIT)), tenant)
                    .stream().filter(item -> !item.read()).toList();
            return pageResult(current, size, unread.size(), slice(unread, current, size), SCAN_LIMIT);
        }
        long total = notificationMapper.selectCount(visible(user, notifyType, keyword));
        List<Notification> rows = notificationMapper.selectList(visible(user, notifyType, keyword)
                .orderByDesc(Notification::getPublishTime)
                .last("LIMIT " + size + " OFFSET " + (long) (current - 1) * size));
        return pageResult(current, size, total, toVOs(user, rows, tenant), null);
    }

    /**
     * 消息中心左侧的分类统计：每类多少条、多少未读。
     *
     * <p>口径同「只看未读」：在最近 {@value #SCAN_LIMIT} 条里数。</p>
     */
    public NotificationSummaryVO summary(LoginUser user) {
        String tenant = tenantOf(user);
        List<NotificationVO> recent = toVOs(user, notificationMapper.selectList(
                visible(user, null, null)
                        .orderByDesc(Notification::getPublishTime)
                        .last("LIMIT " + SCAN_LIMIT)), tenant);
        NotificationCountVO all = new NotificationCountVO(recent.size(),
                (int) recent.stream().filter(item -> !item.read()).count());
        Map<String, NotificationTypeCountVO> byType = new LinkedHashMap<>();
        for (int type : SUMMARY_TYPES) {
            List<NotificationVO> ofType = recent.stream()
                    .filter(item -> item.notifyType() != null && item.notifyType() == type)
                    .toList();
            byType.put(String.valueOf(type), new NotificationTypeCountVO(notifyTypeText(type),
                    ofType.size(), (int) ofType.stream().filter(item -> !item.read()).count()));
        }
        return new NotificationSummaryVO(all, byType, SCAN_LIMIT);
    }

    /**
     * 可见范围：企业级消息 + 发给我的，可按类型与关键字过滤。
     *
     * <p>每次现建一个 wrapper——wrapper 是可变的，复用会被上一次的 {@code last()} 污染。</p>
     */
    private LambdaQueryWrapper<Notification> visible(LoginUser user, Integer notifyType, String keyword) {
        String tenant = tenantOf(user);
        Integer type = notifyType != null && notifyType > 0 ? notifyType : null;
        String kw = keyword == null || keyword.isBlank() ? null : keyword.trim();
        return Wrappers.<Notification>lambdaQuery()
                .eq(Notification::getTenantCode, tenant)
                .eq(type != null, Notification::getNotifyType, type)
                .and(kw != null, w -> w.like(Notification::getTitle, kw)
                        .or().like(Notification::getContent, kw))
                .and(w -> w.eq(Notification::getTargetType, TARGET_TENANT)
                        .or(x -> x.eq(Notification::getTargetType, TARGET_USER)
                                .eq(Notification::getTargetId, user.userId())));
    }

    /** 补上"我读没读"：一次查完这一页的已读记录，别一条一条查 */
    private List<NotificationVO> toVOs(LoginUser user, List<Notification> rows, String tenant) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> ids = rows.stream().map(Notification::getId).toList();
        Set<Long> readIds = notificationReadMapper.selectList(Wrappers.<NotificationRead>lambdaQuery()
                        .eq(NotificationRead::getTenantCode, tenant)
                        .eq(NotificationRead::getUserId, user.userId())
                        .in(NotificationRead::getNotifyId, ids))
                .stream().map(NotificationRead::getNotifyId).collect(Collectors.toSet());
        List<NotificationVO> result = new ArrayList<>(rows.size());
        for (Notification row : rows) {
            result.add(new NotificationVO(
                    String.valueOf(row.getId()),
                    row.getNotifyType(),
                    notifyTypeText(row.getNotifyType()),
                    row.getTitle(),
                    row.getContent(),
                    row.getLinkView(),
                    row.getPublishTime() == null ? null
                            : row.getPublishTime().toString().replace('T', ' ').substring(0, 19),
                    readIds.contains(row.getId())));
        }
        return result;
    }

    private static <T> List<T> slice(List<T> rows, int current, int size) {
        int from = Math.min((current - 1) * size, rows.size());
        int to = Math.min(from + size, rows.size());
        return rows.subList(from, to);
    }

    private static NotificationPageVO pageResult(int current, int size, long total,
                                                 List<NotificationVO> list, Integer scanLimit) {
        return new NotificationPageVO((int) total, current, size, list, scanLimit);
    }

    /** 未读数：前端右上角红点用 */
    public int unreadCount(LoginUser user) {
        String tenant = tenantOf(user);
        List<Notification> rows = notificationMapper.selectList(Wrappers.<Notification>lambdaQuery()
                .eq(Notification::getTenantCode, tenant)
                .and(w -> w.eq(Notification::getTargetType, TARGET_TENANT)
                        .or(x -> x.eq(Notification::getTargetType, TARGET_USER)
                                .eq(Notification::getTargetId, user.userId())))
                .orderByDesc(Notification::getPublishTime)
                .last("LIMIT " + LIST_LIMIT));
        if (rows.isEmpty()) {
            return 0;
        }
        Set<Long> readIds = notificationReadMapper.selectList(Wrappers.<NotificationRead>lambdaQuery()
                        .eq(NotificationRead::getTenantCode, tenant)
                        .eq(NotificationRead::getUserId, user.userId()))
                .stream().map(NotificationRead::getNotifyId).collect(Collectors.toSet());
        return (int) rows.stream().filter(row -> !readIds.contains(row.getId())).count();
    }

    /** 标记一条已读（重复标记不报错） */
    public void markRead(LoginUser user, String notifyId) {
        String tenant = tenantOf(user);
        long id;
        try {
            id = Long.parseLong(notifyId);
        } catch (NumberFormatException e) {
            throw new BizException(40001, "消息 ID 不合法");
        }
        Long exists = notificationReadMapper.selectCount(Wrappers.<NotificationRead>lambdaQuery()
                .eq(NotificationRead::getTenantCode, tenant)
                .eq(NotificationRead::getNotifyId, id)
                .eq(NotificationRead::getUserId, user.userId()));
        if (exists != null && exists > 0) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        notificationReadMapper.insert(NotificationRead.builder()
                .id(idGenerator.nextId())
                .tenantCode(tenant)
                .notifyId(id)
                .userId(user.userId())
                .readTime(now)
                .createTime(now)
                .build());
    }

    /** 全部标记已读：把当前可见的未读一次性写进已读表 */
    public int markAllRead(LoginUser user, Integer notifyType) {
        String tenant = tenantOf(user);
        List<Notification> rows = list(user, notifyType, LIST_LIMIT).stream()
                .filter(item -> !item.read())
                .map(item -> Notification.builder().id(Long.parseLong(item.id())).build())
                .toList();
        int count = 0;
        for (Notification row : rows) {
            markRead(user, String.valueOf(row.getId()));
            count++;
        }
        return count;
    }

    private String tenantOf(LoginUser user) {
        if (user == null || user.userType() != 2) {
            throw new BizException(40301, "仅企业成员可使用消息中心");
        }
        String tenant = user.tenantCode();
        if (tenant == null || tenant.isBlank() || "PLATFORM".equals(tenant)) {
            throw new BizException(40301, "请先完成企业开通后再使用消息中心");
        }
        return tenant;
    }

    private static String limit(String text, int max) {
        if (text == null) {
            return "-";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    public static String notifyTypeText(Integer type) {
        if (type == null) {
            return "";
        }
        return switch (type) {
            case TYPE_SYSTEM -> "系统通知";
            case TYPE_TICKET -> "工单提醒";
            case TYPE_AUDIT -> "审核通知";
            case TYPE_QA -> "质检通知";
            case 5 -> "公告";
            default -> "账单通知";
        };
    }

    /** 前端 tab 用：类型码 → 文案（避免两边各写一份） */
    public Map<String, String> typeTexts() {
        Map<String, String> map = new HashMap<>();
        map.put("1", "系统通知");
        map.put("2", "工单提醒");
        map.put("3", "审核通知");
        map.put("4", "质检通知");
        return map;
    }
}
