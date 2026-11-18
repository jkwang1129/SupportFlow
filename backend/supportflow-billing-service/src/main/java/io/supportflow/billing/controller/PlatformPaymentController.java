package io.supportflow.billing.controller;

import io.supportflow.common.context.LoginUserContext;
import io.supportflow.billing.dto.PaymentOverviewVO;
import io.supportflow.billing.dto.PaymentPageVO;
import io.supportflow.billing.service.PaymentRecordService;
import io.supportflow.common.api.ApiResponse;
import io.supportflow.common.auth.LoginUser;
import io.supportflow.common.exception.BizException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台「收款流水」：**读的是 payment_record（收款事实），不是订单表**。
 *
 * <p>为什么单独一个接口而不是复用 `/api/billing/orders`：订单是"要收多少钱"的意向
 * （待支付、已取消都在里面），收款流水是"真的收到多少"。对账要用后者，
 * 两个口径混在一个接口里，迟早有人拿订单金额当收入。</p>
 *
 * <p>只读：流水的写入在收款链路里（订单支付 / 账期结算），页面连改都改不了。</p>
 */
@RestController
@RequestMapping("/api/billing/platform/payments")
public class PlatformPaymentController {

    private final PaymentRecordService paymentRecordService;

    public PlatformPaymentController(PaymentRecordService paymentRecordService) {
        this.paymentRecordService = paymentRecordService;
    }

    /**
     * 收款流水列表（筛选 + 分页 + 合计 + 概览 + 按支付方式汇总）。
     *
     * @param bizType       业务类型：1-订单（预付费套餐）、2-账期账单（后付费超量）
     * @param payMethodCode 支付方式码：1-支付宝、2-微信、3-对公
     * @param status        1-处理中、2-成功、3-失败（不传=全部）
     */
    @GetMapping
    public ApiResponse<PaymentPageVO> payments(
            @RequestParam(required = false) String tenantCode,
            @RequestParam(required = false) Integer bizType,
            @RequestParam(required = false) Integer payMethodCode,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize
    ) {
        requirePlatform();
        return ApiResponse.ok(paymentRecordService.payments(tenantCode, bizType, payMethodCode, status,
                keyword, from, to, page, pageSize));
    }

    /** 收款概览：累计 / 本月 / 今日（只算成功）+ 订单收款与账单收款各自的笔数 */
    @GetMapping("/overview")
    public ApiResponse<PaymentOverviewVO> overview() {
        requirePlatform();
        return ApiResponse.ok(paymentRecordService.overview());
    }

    private LoginUser requirePlatform() {
        LoginUser user = LoginUserContext.require();
        if (user.userType() != 1) {
            throw new BizException(40302, "只有平台账号能看收款流水");
        }
        return user;
    }
}
