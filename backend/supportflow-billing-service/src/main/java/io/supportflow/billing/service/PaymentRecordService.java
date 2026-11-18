package io.supportflow.billing.service;

import io.supportflow.billing.dto.PayMethodSumVO;
import io.supportflow.billing.dto.PaymentOverviewVO;
import io.supportflow.billing.dto.PaymentPageVO;
import io.supportflow.billing.dto.PaymentRowVO;
import io.supportflow.billing.entity.PaymentRecord;
import io.supportflow.billing.mapper.PaymentRecordMapper;
import io.supportflow.common.id.SnowflakeIdGenerator;
import io.supportflow.common.util.Texts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 收款流水：两条收款链路各写一条 + 平台对账页的查询。

 *
 * <p>谁来调 {@link #recordOrder} / {@link #recordStatement}：</p>
 * <ul>
 *   <li><b>订单支付</b>（预付费：买套餐）——{@code BillingService.payOrder} 成功后；</li>
 *   <li><b>账期账单结算</b>（后付费：超量费）——{@code BillingService.settle} 确认收款后。</li>
 * </ul>
 *
 * <p>三条口径：**只追加**（钱收到就是事实）、**一笔业务一条**（`biz_type + biz_no` 唯一，
 * 三方回调重推也不会写两条）、**流水号唯一**（`pay_no`，重复回调按它兜底）。</p>
 */
@Service
public class PaymentRecordService {

    /** 支付方式码（与 billing_order.pay_method 一致） */
    public static final int METHOD_ALIPAY = 1;
    public static final int METHOD_WECHAT = 2;
    public static final int METHOD_CORP = 3;

    /** 业务类型 */
    public static final int BIZ_ORDER = 1;
    public static final int BIZ_STATEMENT = 2;

    private static final DateTimeFormatter PAY_NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Logger log = LoggerFactory.getLogger(PaymentRecordService.class);

    private final PaymentRecordMapper paymentRecordMapper;
    private final SnowflakeIdGenerator idGenerator;

    public PaymentRecordService(PaymentRecordMapper paymentRecordMapper,
                                SnowflakeIdGenerator idGenerator) {
        this.paymentRecordMapper = paymentRecordMapper;
        this.idGenerator = idGenerator;
    }

    /**
     * 记一笔订单收款（预付费）。
     *
     * @param payNo 三方流水号；没有（平台确认收款 / 本地联调）就由平台生成一个收款单号
     */
    public void recordOrder(String tenantCode, long orderId, String orderNo, int payMethodCode,
                            BigDecimal amount, String payNo, String operator) {
        record(tenantCode, orderId, BIZ_ORDER, orderNo, payMethodCode, amount, payNo, operator,
                "订单收款：" + orderNo);
    }

    /** 记一笔账期账单收款（后付费） */
    public void recordStatement(String tenantCode, String statementNo, int payMethodCode,
                                BigDecimal amount, String payNo, String operator) {
        record(tenantCode, null, BIZ_STATEMENT, statementNo, payMethodCode, amount, payNo, operator,
                "账期账单收款：" + statementNo);
    }

    /**
     * 落一条收款流水（幂等）。
     *
     * <p>失败只记 warn：**收款这件事已经完成**（订单/账单状态都改了），
     * 流水写不进去不该让这笔收款回滚——但也不能当没发生，所以日志里要留下痕迹。</p>
     */
    public void record(String tenantCode, Long orderId, int bizType, String bizNo, int payMethodCode,
                       BigDecimal amount, String payNo, String operator, String remark) {
        try {
            int code = normalizeMethod(payMethodCode);
            String receiptNo = payNo == null || payNo.isBlank() ? nextPayNo() : payNo.trim();
            int inserted = paymentRecordMapper.insertIgnore(PaymentRecord.builder()
                    .id(idGenerator.nextId())
                    .tenantCode(tenantCode)
                    .orderId(orderId)
                    .bizType((short) bizType)
                    .bizNo(bizNo)
                    .payNo(receiptNo)
                    // 这一列存三方口径的名称（ALIPAY / WECHAT / CORP），与真实网关回调对齐；
                    // 页面展示用 pay_method_code 翻译，两列各管一头（见增量脚本注释）
                    .payMethod(methodKey(code))
                    .payMethodCode((short) code)
                    .amount(amount == null ? BigDecimal.ZERO : amount)
                    .status((short) 2)
                    .payTime(LocalDateTime.now())
                    .remark(remark)
                    .createTime(LocalDateTime.now())
                    .creator(operator == null ? "系统" : operator)
                    .build());
            if (inserted == 0) {
                log.info("这笔业务已有收款流水，跳过 tenant={} bizType={} bizNo={}",
                        tenantCode, bizType, bizNo);
            } else {
                log.info("收款流水已记录 tenant={} bizType={} bizNo={} method={} amount={} payNo={}",
                        tenantCode, bizType, bizNo, methodName(code), amount, receiptNo);
            }
        } catch (Exception e) {
            log.warn("写收款流水失败 tenant={} bizNo={} error={}", tenantCode, bizNo, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 对账查询

    public PaymentPageVO payments(String tenantCode, Integer bizType, Integer payMethodCode,
                                  Integer status, String keyword, String from, String to,
                                  int page, int pageSize) {
        String tenant = Texts.blankToNull(tenantCode);
        String kw = Texts.blankToNull(keyword);
        String start = Texts.blankToNull(from);
        String end = Texts.blankToNull(to);
        int current = Math.max(1, page);
        int size = Math.min(Math.max(1, pageSize), 100);
        long total = paymentRecordMapper.countPayments(tenant, bizType, payMethodCode, status, kw,
                start, end);
        List<Map<String, Object>> rows = paymentRecordMapper.selectPayments(tenant, bizType,
                payMethodCode, status, kw, start, end, size, (long) (current - 1) * size);
        List<PaymentRowVO> list = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            int rowBizType = intOf(row.get("bizType"));
            int rowMethodCode = intOf(row.get("payMethodCode"));
            int rowStatus = intOf(row.get("status"));
            list.add(new PaymentRowVO(stringOf(row.get("id")), stringOf(row.get("tenantCode")),
                    stringOf(row.get("orderId")), rowBizType, stringOf(row.get("bizNo")),
                    stringOf(row.get("payNo")), stringOf(row.get("payMethod")), rowMethodCode,
                    decimalOf(row.get("amount")), rowStatus, stringOf(row.get("payTime")),
                    stringOf(row.get("remark")), stringOf(row.get("creator")),
                    bizTypeText(rowBizType), methodName(rowMethodCode), statusText(rowStatus)));
        }
        // 合计只算成功收款：这里把 status 固定传 2（页面上的 status 筛选是给列表用的）
        BigDecimal amount = paymentRecordMapper.sumSucceeded(tenant, bizType, payMethodCode,
                2, kw, start, end);
        List<PayMethodSumVO> byMethod = new ArrayList<>();
        for (Map<String, Object> row : paymentRecordMapper.selectByMethod(start, end)) {
            byMethod.add(new PayMethodSumVO(intOf(row.get("payMethodCode")),
                    stringOf(row.get("payMethod")), (long) intOf(row.get("count")),
                    decimalOf(row.get("amount"))));
        }
        return new PaymentPageVO(total, current, size, list, amount, overview(), byMethod);
    }

    private static String stringOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 收款概览：累计 / 本月 / 今日（只算成功）+ 订单收款与账单收款各自的笔数 */
    public PaymentOverviewVO overview() {
        Map<String, Object> raw = paymentRecordMapper.selectOverview();
        return new PaymentOverviewVO(decimalOf(raw == null ? null : raw.get("total")),
                intOf(raw == null ? null : raw.get("totalCount")),
                decimalOf(raw == null ? null : raw.get("month")),
                decimalOf(raw == null ? null : raw.get("today")),
                intOf(raw == null ? null : raw.get("orderCount")),
                intOf(raw == null ? null : raw.get("statementCount")));
    }

    // ------------------------------------------------------------------ 小工具

    /** 收款单号：平台自己收的（对公 / 平台确认）也给一个可对账的号，方便和企业对流水 */
    private static String nextPayNo() {
        return "PAY" + LocalDateTime.now().format(PAY_NO_TIME) + ThreadLocalRandom.current().nextInt(1000, 9999);
    }

    private static int normalizeMethod(int code) {
        return code == METHOD_ALIPAY || code == METHOD_WECHAT ? code : METHOD_CORP;
    }

    public static String methodName(int code) {
        return switch (code) {
            case METHOD_ALIPAY -> "支付宝";
            case METHOD_WECHAT -> "微信支付";
            default -> "对公转账";
        };
    }

    /** 三方口径的方式名（真实网关接入后回调里带的就是它） */
    public static String methodKey(int code) {
        return switch (code) {
            case METHOD_ALIPAY -> "ALIPAY";
            case METHOD_WECHAT -> "WECHAT";
            default -> "CORP";
        };
    }

    public static String bizTypeText(int bizType) {
        return bizType == BIZ_STATEMENT ? "账期账单" : "订单·套餐";
    }

    private static String statusText(int status) {
        return switch (status) {
            case 2 -> "已到账";
            case 3 -> "失败";
            default -> "处理中";
        };
    }

    private static int intOf(Object raw) {
        if (raw instanceof Number number) {
            return number.intValue();
        }
        try {
            return raw == null ? 0 : Integer.parseInt(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static BigDecimal decimalOf(Object raw) {
        if (raw instanceof BigDecimal decimal) {
            return decimal;
        }
        if (raw instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        try {
            return raw == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(raw));
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }
}
