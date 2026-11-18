package io.supportflow.billing.service;

import io.supportflow.billing.dto.ConsolePaymentConfigVO;
import io.supportflow.billing.dto.CorpAccount;
import io.supportflow.billing.dto.PaymentConfigBody;
import io.supportflow.billing.dto.PaymentConfigVO;
import io.supportflow.billing.dto.PlanAdminVO;
import io.supportflow.billing.dto.PlanPriceBody;
import io.supportflow.billing.entity.PaymentConfig;
import io.supportflow.billing.entity.Plan;
import io.supportflow.billing.mapper.PaymentConfigMapper;
import io.supportflow.billing.mapper.PlanMapper;
import io.supportflow.common.audit.AuditLogClient;
import io.supportflow.common.auth.LoginUser;
import io.supportflow.common.exception.BizException;
import io.supportflow.common.id.SnowflakeIdGenerator;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 平台设置里的"钱"两块：套餐价格 + 支付配置。
 *
 * <p>为什么放在 billing-service 而不是 ops-service：`plan` 与 `payment_config` 都在 billing_db，
 * 而且改价格会直接影响下单与账单——让钱的库只有一个写入口。</p>
 *
 * <p>支付配置在库里是**一个渠道一行**（1-支付宝、2-微信、3-对公转账），
 * 但原型上是一张表单，所以这里对外给一个聚合对象、对内写三行，
 * 前端不用知道渠道拆分的细节。</p>
 */
@Service
public class PlatformBillingSettingsService {

    private static final Logger log = LoggerFactory.getLogger(PlatformBillingSettingsService.class);

    /** 审计模块名（与平台页筛选器一致） */
    private static final String MODULE = "平台设置";

    private static final int CHANNEL_ALIPAY = 1;
    private static final int CHANNEL_WECHAT = 2;
    private static final int CHANNEL_CORP = 3;

    private final PlanMapper planMapper;
    private final PaymentConfigMapper paymentConfigMapper;
    private final SnowflakeIdGenerator idGenerator;
    private final AuditLogClient auditLogClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PlatformBillingSettingsService(PlanMapper planMapper,
                                          PaymentConfigMapper paymentConfigMapper,
                                          SnowflakeIdGenerator idGenerator,
                                          AuditLogClient auditLogClient) {
        this.planMapper = planMapper;
        this.paymentConfigMapper = paymentConfigMapper;
        this.idGenerator = idGenerator;
        this.auditLogClient = auditLogClient;
    }

    // ------------------------------------------------------------------ 套餐价格

    /** 全部套餐（含停用），平台设置页要能改价、也能上下架 */
    public List<PlanAdminVO> plans() {
        List<Plan> plans = planMapper.selectList(Wrappers.<Plan>lambdaQuery()
                .eq(Plan::getDeleted, false)
                .orderByAsc(Plan::getId));
        List<PlanAdminVO> list = new ArrayList<>(plans.size());
        for (Plan plan : plans) {
            list.add(new PlanAdminVO(String.valueOf(plan.getId()), plan.getPlanCode(),
                    plan.getPlanName(), plan.getPriceYearly(), plan.getPriceMonthly(),
                    plan.getQuotaRobotSessions(), plan.getQuotaSeats(), plan.getQuotaAiCalls(),
                    plan.getOveragePricePerK(), plan.getIsEnabled()));
        }
        return list;
    }

    /**
     * 保存套餐价格（按 id 全量更新提交的那几条）。
     *
     * <p>只改"价格与配额"这些运营能调的项：套餐编码、名称不动
     * （编码是代码里认的，改了会让"当前套餐"判断失效）。</p>
     */
    @Transactional
    public int savePlans(LoginUser operator, List<PlanPriceBody> body) {
        if (body == null || body.isEmpty()) {
            throw new BizException(40001, "没有要保存的套餐");
        }
        int saved = 0;
        List<String> summary = new ArrayList<>();
        for (PlanPriceBody item : body) {
            if (item == null || item.id() == null || item.id().isBlank()) {
                continue;
            }
            Plan plan = planMapper.selectById(Long.parseLong(item.id()));
            if (plan == null || Boolean.TRUE.equals(plan.getDeleted())) {
                throw new BizException(40401, "套餐不存在：" + item.id());
            }
            requireNonNegative(item.priceYearly(), "年付价格");
            requireNonNegative(item.priceMonthly(), "月付价格");
            requireNonNegative(item.overagePricePerK(), "超量单价");

            Plan update = new Plan();
            update.setId(plan.getId());
            update.setPriceYearly(item.priceYearly());
            update.setPriceMonthly(item.priceMonthly());
            if (item.quotaRobotSessions() != null) {
                update.setQuotaRobotSessions(item.quotaRobotSessions());
            }
            if (item.quotaSeats() != null) {
                update.setQuotaSeats(item.quotaSeats());
            }
            if (item.quotaAiCalls() != null) {
                update.setQuotaAiCalls(item.quotaAiCalls());
            }
            if (item.overagePricePerK() != null) {
                update.setOveragePricePerK(item.overagePricePerK());
            }
            if (item.isEnabled() != null) {
                update.setIsEnabled(item.isEnabled());
            }
            update.setUpdateTime(LocalDateTime.now());
            update.setEditor(operator == null ? "platform" : operator.name());
            planMapper.updateById(update);
            saved++;
            summary.add(plan.getPlanName() + " 年付 ¥" + item.priceYearly() + " / 月付 ¥" + item.priceMonthly());
        }
        log.info("平台设置：套餐价格已更新 {} 条 by={}", saved, operator == null ? "platform" : operator.name());
        auditLogClient.record(operator, MODULE, "保存套餐价格", "套餐", null, true,
                "更新 " + saved + " 档套餐价格：" + String.join("；", summary)
                        + "（「版本与升级」页同步生效）");
        return saved;
    }

    // ------------------------------------------------------------------ 支付配置

    /** 读支付配置（聚合三个渠道成一张表单） */
    public PaymentConfigVO paymentConfig() {
        PaymentConfig alipay = channelConfig(CHANNEL_ALIPAY);
        PaymentConfig wechat = channelConfig(CHANNEL_WECHAT);
        PaymentConfig corp = channelConfig(CHANNEL_CORP);
        CorpAccount corpAccount = parseCorpAccount(corp == null ? null : corp.getConfigJson());

        return new PaymentConfigVO(
                alipay != null && !Boolean.FALSE.equals(alipay.getIsEnabled())
                        || wechat != null && !Boolean.FALSE.equals(wechat.getIsEnabled()),
                alipay == null ? null : alipay.getMerchantId(),
                wechat == null ? null : wechat.getMerchantId(),
                corpAccount.accountName(), corpAccount.accountNo(), corpAccount.bankName(),
                alipay != null || wechat != null || corp != null);
    }

    /**
     * 保存支付配置：一次写三个渠道。
     *
     * <p>对公转账没有"开关"（它是线下转账，永远可用），所以那个开关只控制支付宝与微信。</p>
     */
    @Transactional
    public PaymentConfigVO savePaymentConfig(LoginUser operator, PaymentConfigBody body) {
        if (body == null) {
            throw new BizException(40001, "缺少支付配置内容");
        }
        boolean online = !Boolean.FALSE.equals(body.onlinePayEnabled());
        String operatorName = operator == null ? "platform" : operator.name();
        upsertChannel(CHANNEL_ALIPAY, body.alipayMerchantId(), null, online, operatorName);
        upsertChannel(CHANNEL_WECHAT, body.wechatMerchantId(), null, online, operatorName);
        upsertChannel(CHANNEL_CORP, null,
                toJson(new CorpAccount(body.corpAccountName(), body.corpAccountNo(), body.corpBankName())),
                true, operatorName);

        log.info("平台设置：支付配置已更新 在线支付={} by={}", online, operatorName);
        auditLogClient.record(operator, MODULE, "保存支付配置", "支付配置", null, true,
                "在线支付（支付宝/微信）=" + (online ? "启用" : "停用")
                        + "，支付宝商户号=" + mask(body.alipayMerchantId())
                        + "，微信商户号=" + mask(body.wechatMerchantId())
                        + "，对公账户=" + (body.corpAccountName() == null ? "未填" : body.corpAccountName()));
        return paymentConfig();
    }

    // ------------------------------------------------------------------ 内部

    private PaymentConfig channelConfig(int channel) {
        return paymentConfigMapper.selectOne(Wrappers.<PaymentConfig>lambdaQuery()
                .eq(PaymentConfig::getPayChannel, channel)
                .eq(PaymentConfig::getDeleted, false)
                .last("LIMIT 1"));
    }

    private void upsertChannel(int channel, String merchantId, String configJson,
                               boolean enabled, String operatorName) {
        PaymentConfig current = channelConfig(channel);
        LocalDateTime now = LocalDateTime.now();
        if (current == null) {
            paymentConfigMapper.insert(PaymentConfig.builder()
                    .id(idGenerator.nextId())
                    .payChannel(channel)
                    .merchantId(merchantId)
                    .configJson(configJson == null ? "{}" : configJson)
                    .isEnabled(enabled)
                    .createTime(now)
                    .updateTime(now)
                    .creator(operatorName)
                    .deleted(false)
                    .build());
            return;
        }
        PaymentConfig update = new PaymentConfig();
        update.setId(current.getId());
        update.setMerchantId(merchantId);
        if (configJson != null) {
            update.setConfigJson(configJson);
        }
        update.setIsEnabled(enabled);
        update.setUpdateTime(now);
        update.setEditor(operatorName);
        paymentConfigMapper.updateById(update);
    }

    private String toJson(CorpAccount account) {
        try {
            return objectMapper.writeValueAsString(account);
        } catch (Exception e) {
            return "{}";
        }
    }

    private CorpAccount parseCorpAccount(String json) {
        if (json == null || json.isBlank()) {
            return new CorpAccount(null, null, null);
        }
        try {
            CorpAccount parsed = objectMapper.readValue(json, CorpAccount.class);
            return parsed == null ? new CorpAccount(null, null, null) : parsed;
        } catch (Exception e) {
            log.warn("解析 payment_config.config_json 失败：{}", e.getMessage());
            return new CorpAccount(null, null, null);
        }
    }

    private static void requireNonNegative(BigDecimal value, String label) {
        if (value != null && value.compareTo(BigDecimal.ZERO) < 0) {
            throw new BizException(40001, label + "不能为负数");
        }
    }

    /** 商户号只留首尾，审计日志里不写全（避免敏感信息进日志） */
    private static String mask(String value) {
        if (value == null || value.isBlank()) {
            return "未填";
        }
        String text = value.trim();
        return text.length() <= 6 ? text : text.substring(0, 3) + "****" + text.substring(text.length() - 3);
    }

    /**
     * 收银台读的支付配置（**登录即可读**，企业账号也要用）。
     *
     * <p>与平台设置页那份的差别，就是"能给企业看到什么"：</p>
     *
     * <ul>
     *   <li>在线支付开关按渠道给（支付宝 / 微信各一个布尔），页面据此只灰掉关掉的那个；</li>
     *   <li>商户号**掩码**：它的用途只是"扫码时对一眼收的是不是这家"，不必给全；</li>
     *   <li>对公账户**给全**（开户名 / 账号 / 开户行）：客户要照着它转账，掩码就没法付钱了。</li>
     * </ul>
     *
     * <p>线上真正能不能支付**不看这里的字段**，而是由 `payOrder` 再校验一次：
     * 前端藏起来的按钮不算数，服务端拒绝才算生效。</p>
     */
    public ConsolePaymentConfigVO consolePaymentConfig() {
        PaymentConfig alipay = channelConfig(CHANNEL_ALIPAY);
        PaymentConfig wechat = channelConfig(CHANNEL_WECHAT);
        PaymentConfig corp = channelConfig(CHANNEL_CORP);
        CorpAccount corpAccount = parseCorpAccount(corp == null ? null : corp.getConfigJson());
        boolean alipayEnabled = alipay != null && !Boolean.FALSE.equals(alipay.getIsEnabled());
        boolean wechatEnabled = wechat != null && !Boolean.FALSE.equals(wechat.getIsEnabled());
        // 汇总口径：两个在线渠道都关掉才是"在线支付不可用"（只关微信不影响支付宝）
        return new ConsolePaymentConfigVO(alipayEnabled, wechatEnabled,
                alipayEnabled || wechatEnabled,
                mask(alipay == null ? null : alipay.getMerchantId()),
                mask(wechat == null ? null : wechat.getMerchantId()),
                corpAccount.accountName(), corpAccount.accountNo(), corpAccount.bankName(),
                corpAccount.accountNo() != null && !corpAccount.accountNo().isBlank(),
                alipay != null || wechat != null || corp != null);
    }

    /**
     * 某个支付渠道现在能不能收钱（下单支付时的执行点）。
     *
     * <p>渠道码与 `billing_order.pay_method` 是同一套：1-支付宝、2-微信、3-对公。
     * 对公转账**没有开关**（它是线下转账，永远可用），所以这里只对 1 / 2 做判断。</p>
     */
    public boolean channelEnabled(int payMethod) {
        if (payMethod == CHANNEL_CORP) {
            return true;
        }
        if (payMethod != CHANNEL_ALIPAY && payMethod != CHANNEL_WECHAT) {
            return false;
        }
        PaymentConfig config = channelConfig(payMethod);
        return config != null && !Boolean.FALSE.equals(config.getIsEnabled());
    }
}
