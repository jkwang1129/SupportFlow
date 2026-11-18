package io.supportflow.customer.service.session;

import io.supportflow.common.auth.LoginUser;
import io.supportflow.common.exception.BizException;
import io.supportflow.common.id.SnowflakeIdGenerator;
import io.supportflow.common.util.Texts;
import io.supportflow.customer.dto.session.QuickReplyBody;
import io.supportflow.customer.dto.session.QuickReplySaveVO;
import io.supportflow.customer.dto.session.QuickReplyVO;
import io.supportflow.customer.entity.QuickReply;
import io.supportflow.customer.mapper.QuickReplyMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 快捷话术（工作台 Alt+1~9）。
 *
 * <p>排序口径写死一处：**启用中的话术按「使用次数倒序 → sort_no → id」排**，
 * 前 9 条依次绑定 Alt+1 ~ Alt+9。这样坐席最常发的话术会自己浮到 Alt+1，
 * 运营想固定顺序就把 sort_no 调小。</p>
 *
 * <p>为什么不让它无限往上加：Alt 只到 9，超过 9 条的话术仍然能搜到、能用，
 * 但没有全局快捷键——列表里会把快捷键标出来，不让人猜。</p>
 */
@Service
public class QuickReplyService {

    private static final Logger log = LoggerFactory.getLogger(QuickReplyService.class);

    /** 全局快捷键上限（Alt+1 ~ Alt+9） */
    public static final int SHORTCUT_LIMIT = 9;

    private static final int TITLE_MAX = 64;
    private static final int CONTENT_MAX = 512;

    private final QuickReplyMapper quickReplyMapper;
    private final SnowflakeIdGenerator idGenerator;

    public QuickReplyService(QuickReplyMapper quickReplyMapper, SnowflakeIdGenerator idGenerator) {
        this.quickReplyMapper = quickReplyMapper;
        this.idGenerator = idGenerator;
    }

    /** 话术列表（工作台与设置页共用）：按排序口径返回，并标出 Alt 序号 */
    public List<QuickReplyVO> list(String tenantCode, boolean onlyEnabled) {
        List<QuickReply> rows = quickReplyMapper.selectList(Wrappers.<QuickReply>lambdaQuery()
                .eq(QuickReply::getTenantCode, tenantCode)
                .eq(QuickReply::getDeleted, false)
                .eq(onlyEnabled, QuickReply::getIsEnabled, true)
                .orderByDesc(QuickReply::getUseCount)
                .orderByAsc(QuickReply::getSortNo)
                .orderByAsc(QuickReply::getId));
        List<QuickReplyVO> result = new ArrayList<>(rows.size());
        int shortcut = 0;
        for (QuickReply row : rows) {
            boolean enabled = !Boolean.FALSE.equals(row.getIsEnabled());
            Integer alt = null;
            if (enabled && shortcut < SHORTCUT_LIMIT) {
                alt = ++shortcut;
            }
            result.add(toView(row, alt));
        }
        return result;
    }

    @Transactional
    public QuickReplySaveVO save(LoginUser operator, Long id, QuickReplyBody body) {
        if (body == null || body.title() == null || body.title().isBlank()) {
            throw new BizException(40001, "请填写话术标题");
        }
        if (body.content() == null || body.content().isBlank()) {
            throw new BizException(40001, "请填写话术内容");
        }
        String title = body.title().trim();
        String content = body.content().trim();
        if (title.length() > TITLE_MAX) {
            throw new BizException(40001, "标题最长 " + TITLE_MAX + " 个字符");
        }
        if (content.length() > CONTENT_MAX) {
            throw new BizException(40001, "话术内容最长 " + CONTENT_MAX + " 个字符");
        }
        String tenant = operator.tenantCode();
        LocalDateTime now = LocalDateTime.now();
        long targetId;
        if (id == null) {
            targetId = idGenerator.nextId();
            quickReplyMapper.insert(QuickReply.builder()
                    .id(targetId)
                    .tenantCode(tenant)
                    .title(title)
                    .content(content)
                    .category(Texts.blankToNull(body.category()))
                    .sortNo(body.sortNo() == null ? 100 : body.sortNo())
                    .isEnabled(!Boolean.FALSE.equals(body.isEnabled()))
                    .useCount(0)
                    .createTime(now)
                    .updateTime(now)
                    .creator(operator.name())
                    .deleted(false)
                    .build());
        } else {
            QuickReply current = require(tenant, id);
            QuickReply update = new QuickReply();
            update.setId(current.getId());
            update.setTitle(title);
            update.setContent(content);
            update.setCategory(Texts.blankToNull(body.category()));
            if (body.sortNo() != null) {
                update.setSortNo(body.sortNo());
            }
            if (body.isEnabled() != null) {
                update.setIsEnabled(body.isEnabled());
            }
            update.setUpdateTime(now);
            update.setEditor(operator.name());
            quickReplyMapper.updateById(update);
            targetId = id;
        }
        log.info("快捷话术已保存 tenant={} id={} 操作人={}", tenant, targetId, operator.name());
        // 返回列表：页面直接刷新，快捷键序号由服务端算
        return new QuickReplySaveVO(String.valueOf(targetId), list(tenant, false));
    }

    @Transactional
    public void remove(LoginUser operator, long id) {
        QuickReply current = require(operator.tenantCode(), id);
        quickReplyMapper.deleteById(current.getId());
        log.info("快捷话术已删除 tenant={} id={} 操作人={}", operator.tenantCode(), id, operator.name());
    }

    /** 坐席用了一次：原子累加使用次数（并发安全），返回最新列表 */
    @Transactional
    public List<QuickReplyVO> use(String tenantCode, long id) {
        require(tenantCode, id);
        quickReplyMapper.bumpUseCount(tenantCode, id);
        return list(tenantCode, false);
    }

    /**
     * 一键预置常用话术（首次使用的租户点一下就有 6 条能用的）。
     *
     * <p>幂等：已经有话术的租户直接返回现有列表，不会重复插。</p>
     */
    @Transactional
    public List<QuickReplyVO> initDefaults(LoginUser operator) {
        String tenant = operator.tenantCode();
        Long existing = quickReplyMapper.selectCount(Wrappers.<QuickReply>lambdaQuery()
                .eq(QuickReply::getTenantCode, tenant)
                .eq(QuickReply::getDeleted, false));
        if (existing != null && existing > 0) {
            return list(tenant, false);
        }
        LocalDateTime now = LocalDateTime.now();
        int sort = 10;
        for (String[] item : DEFAULT_REPLIES) {
            quickReplyMapper.insert(QuickReply.builder()
                    .id(idGenerator.nextId())
                    .tenantCode(tenant)
                    .title(item[0])
                    .content(item[1])
                    .category(item[2])
                    .sortNo(sort)
                    .isEnabled(true)
                    .useCount(0)
                    .createTime(now)
                    .updateTime(now)
                    .creator(operator.name())
                    .deleted(false)
                    .build());
            sort += 10;
        }
        log.info("快捷话术已预置 tenant={} 条数={} 操作人={}", tenant, DEFAULT_REPLIES.size(), operator.name());
        return list(tenant, false);
    }

    /** 预置话术：问候 / 稍等 / 转人工 / 结束 / 致歉 / 追问订单号（覆盖大多数场景） */
    private static final List<String[]> DEFAULT_REPLIES = List.of(
            // 问候语不带平台品牌：预置数据会直接发给租户的客户，写上"SupportFlow 客服"等于替租户改了品牌
            new String[]{"问候语", "您好，很高兴为您服务～ 请问有什么可以帮您？", "开场"},
            new String[]{"稍等", "稍等一下，我帮您查一下，马上回复您。", "通用"},
            new String[]{"请稍候（较长）", "您的问题我需要核对一下资料，预计 1 分钟内给您答复，请稍候。", "通用"},
            new String[]{"转人工", "好的，正在为您转接人工客服，请稍候。", "通用"},
            new String[]{"致歉", "非常抱歉给您带来不便，我们马上为您处理。", "致歉"},
            new String[]{"追问订单号", "麻烦提供一下订单号（或下单手机号），我马上帮您核实～", "售前/售后"},
            new String[]{"结束语", "还有其他可以帮您的吗？如果没有，我先不打扰您了，祝您生活愉快～", "结束"},
            new String[]{"发票说明", "发票会在确认收货后 3 个工作日内开具，电子发票会发到您下单时填写的邮箱。", "售后"},
            new String[]{"退款时效", "退款到账时间取决于支付方式：微信/支付宝 1 个工作日，银行卡 1-3 个工作日，信用卡最长 15 个工作日。", "售后"}
    );

    private QuickReply require(String tenantCode, long id) {
        QuickReply row = quickReplyMapper.selectOne(Wrappers.<QuickReply>lambdaQuery()
                .eq(QuickReply::getId, id)
                .eq(QuickReply::getTenantCode, tenantCode)
                .eq(QuickReply::getDeleted, false)
                .last("LIMIT 1"));
        if (row == null) {
            throw new BizException(40401, "快捷话术不存在：" + id);
        }
        return row;
    }

    private QuickReplyVO toView(QuickReply row, Integer alt) {
        // alt 为空 = 这条话术没有全局快捷键（第 10 条起）
        return new QuickReplyVO(String.valueOf(row.getId()), row.getTitle(), row.getContent(),
                row.getCategory(), row.getSortNo() == null ? 0 : row.getSortNo(),
                !Boolean.FALSE.equals(row.getIsEnabled()),
                row.getUseCount() == null ? 0L : row.getUseCount(),
                alt, alt == null ? null : "Alt+" + alt,
                row.getUpdateTime() == null ? null : row.getUpdateTime().toString());
    }

}
