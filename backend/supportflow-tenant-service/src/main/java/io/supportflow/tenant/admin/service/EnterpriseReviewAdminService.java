package io.supportflow.tenant.admin.service;

import io.supportflow.common.exception.BizException;
import io.supportflow.common.id.SnowflakeIdGenerator;
import io.supportflow.common.audit.AuditLogClient;
import io.supportflow.common.auth.LoginUser;
import io.supportflow.tenant.admin.dto.ReviewPageVO;
import io.supportflow.tenant.entity.Enterprise;
import io.supportflow.tenant.entity.EnterpriseReview;
import io.supportflow.tenant.entity.Tenant;
import io.supportflow.tenant.internal.BillingTrialClient;
import io.supportflow.tenant.internal.RegistrationPolicyClient;
import io.supportflow.tenant.internal.UserTenantBackfillClient;
import io.supportflow.tenant.mapper.EnterpriseMapper;
import io.supportflow.tenant.mapper.EnterpriseReviewMapper;
import io.supportflow.tenant.mapper.TenantMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 企业审核台：待审核分页、详情、通过（创建租户）、驳回。
 */
@Service
public class EnterpriseReviewAdminService {

    private static final Logger log = LoggerFactory.getLogger(EnterpriseReviewAdminService.class);
    private static final DateTimeFormatter CODE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final AtomicInteger DAY_SEQ = new AtomicInteger(0);

    private final EnterpriseReviewMapper reviewMapper;
    private final EnterpriseMapper enterpriseMapper;
    private final TenantMapper tenantMapper;
    private final SnowflakeIdGenerator idGenerator;
    private final UserTenantBackfillClient backfillClient;
    private final BillingTrialClient trialClient;
    /** 注册策略：审核通过时按平台配置的试用天数开试用 */
    private final RegistrationPolicyClient registrationPolicyClient;
    /** 审计日志：企业审核是平台侧最关键的准入动作，通过 / 驳回都要留痕 */
    private final AuditLogClient auditLogClient;

    public EnterpriseReviewAdminService(
            EnterpriseReviewMapper reviewMapper,
            EnterpriseMapper enterpriseMapper,
            TenantMapper tenantMapper,
            SnowflakeIdGenerator idGenerator,
            UserTenantBackfillClient backfillClient,
            BillingTrialClient trialClient,
            RegistrationPolicyClient registrationPolicyClient,
            AuditLogClient auditLogClient
    ) {
        this.reviewMapper = reviewMapper;
        this.enterpriseMapper = enterpriseMapper;
        this.tenantMapper = tenantMapper;
        this.idGenerator = idGenerator;
        this.backfillClient = backfillClient;
        this.trialClient = trialClient;
        this.registrationPolicyClient = registrationPolicyClient;
        this.auditLogClient = auditLogClient;
    }

    /** 审计用的模块名（与平台页筛选器一致） */
    private static final String AUDIT_MODULE = "企业审核";

    @Transactional(readOnly = true)
    public IPage<ReviewPageVO> page(int pageNum, int pageSize, Integer status, String keyword) {
        int safePage = Math.max(pageNum, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), 100);
        String safeKeyword = keyword == null || keyword.isBlank() ? null : keyword.trim();
        return reviewMapper.selectReviewAdminPage(new Page<>(safePage, safeSize), status, safeKeyword);
    }

    @Transactional(readOnly = true)
    public ReviewPageVO detail(long reviewId) {
        EnterpriseReview review = reviewMapper.selectById(reviewId);
        if (review == null || Boolean.TRUE.equals(review.getDeleted())) {
            throw new BizException(40401, "审核记录不存在");
        }
        Enterprise enterprise = enterpriseMapper.selectById(review.getEnterpriseId());
        if (enterprise == null) {
            throw new BizException(40401, "企业信息不存在");
        }
        return toVO(review, enterprise);
    }

    @Transactional
    public ReviewPageVO approve(LoginUser operator, long reviewId) {
        return approveInternal(reviewId, operator, null);
    }

    /**
     * 自动放行：平台关掉「新注册企业需运营审核」，或企业邮箱域名命中白名单时，
     * 提交那一刻直接开通租户，不进人工队列。
     *
     * <p>与人工审批共用 {@link #approveInternal}，不是两套流程：创建租户、回填企业、
     * 开免费版试用、写审计、发通知这些事只写一份，区别只有"审核人是谁"和"审计里写的命中原因"。
     * 各写一份的话，早晚出现"自动通过的那批没给试用 / 没回填租户号"这类差一截的问题。</p>
     */
    @Transactional
    public ReviewPageVO autoApprove(long reviewId, String reason) {
        return approveInternal(reviewId, null, reason);
    }

    /**
     * 通过审核的公共实现。
     *
     * @param operator 人工审批的操作人；传 null 表示系统自动放行（审计里记成"系统"，
     *                 审核记录 {@code reviewer_id} 留空，页面据此显示"系统自动放行"）
     * @param autoReason 自动放行的命中原因（人工审批传 null）
     */
    private ReviewPageVO approveInternal(long reviewId, LoginUser operator, String autoReason) {
        Long reviewerId = operator == null ? null : operator.userId();
        ReviewPageVO vo = detail(reviewId);
        if (vo.getStatus() != 1) {
            throw new BizException(40001, "仅待审核记录可执行通过操作");
        }
        String tenantCode = generateTenantCode();

        tenantMapper.insert(Tenant.builder()
                .id(idGenerator.nextId())
                .tenantCode(tenantCode)
                .enterpriseId(Long.parseLong(vo.getEnterpriseId()))
                .status(1)
                .isolationMode(1)
                .creator(reviewerId == null ? "REVIEW_AUTO_APPROVE" : "REVIEW_APPROVE")
                .deleted(false)
                .build());

        LambdaUpdateWrapper<Enterprise> enterpriseUpdate = Wrappers.lambdaUpdate(Enterprise.class)
                .eq(Enterprise::getId, Long.parseLong(vo.getEnterpriseId()))
                .eq(Enterprise::getDeleted, false)
                .set(Enterprise::getStatus, 2)
                .set(Enterprise::getTenantCode, tenantCode)
                .set(Enterprise::getEditor, reviewerId == null ? "SYSTEM_AUTO_APPROVE" : String.valueOf(reviewerId))
                .set(Enterprise::getUpdateTime, LocalDateTime.now());
        enterpriseMapper.update(null, enterpriseUpdate);

        LambdaUpdateWrapper<EnterpriseReview> reviewUpdate = Wrappers.lambdaUpdate(EnterpriseReview.class)
                .eq(EnterpriseReview::getId, reviewId)
                .set(EnterpriseReview::getStatus, 2)
                .set(EnterpriseReview::getTenantCode, tenantCode)
                // 自动放行没有人工审核人：留着 null，不写 0 这种"看起来像用户 ID"的假值
                .set(reviewerId != null, EnterpriseReview::getReviewerId, reviewerId)
                .set(EnterpriseReview::getReviewTime, LocalDateTime.now())
                .set(EnterpriseReview::getUpdateTime, LocalDateTime.now());
        reviewMapper.update(null, reviewUpdate);

        if (vo.getApplicantId() != null) {
            backfillClient.backfillTenantCode(Long.parseLong(vo.getApplicantId()), tenantCode);
        }
        // 试用额度：审核通过 = 租户开好了，直接给一档免费版（配额很小，用完提示升级）
        // 试用天数来自平台「注册与审核」的策略（拿不到按 14 天），不是写死的一个月
        startTrialAfterCommit(tenantCode);
        log.info("企业审核通过 reviewId={}, tenantCode={}, reviewerId={}, auto={}",
                reviewId, tenantCode, reviewerId, autoReason != null);
        auditLogClient.record(operator, AUDIT_MODULE, "通过企业审核", "企业", vo.getApplyNo(), true,
                autoReason == null
                        ? "通过「" + vo.getCompanyName() + "」的企业开通申请，租户 " + tenantCode + " 已开通"
                        : "【自动放行】" + autoReason + "，已自动通过「" + vo.getCompanyName()
                          + "」的企业开通申请，租户 " + tenantCode + " 已开通");
        return detail(reviewId);
    }

    /**
     * 事务提交后再去开免费版试用。
     *
     * <p><b>为什么不能直接在上面那一行调</b>：开通试用要跨服务把 {@code tenant.plan_id} 与
     * {@code tenant_quota} 写回来，而 tenant 这一行是<b>本事务刚插进去、还没提交</b>的——
     * 另一个连接（哪怕是自己服务的另一次请求）在 READ COMMITTED 下看不到它，回写就会以
     * 「租户不存在」失败；失败又被链路吞成一行 warn（billing 侧 catch 后返回 false，
     * tenant 侧照样打 INFO），于是表现成"企业开通了、套餐却没到账"，页面上完全看不出错在哪。
     * 踩过：早期用审核台开通的 T202609050000001 就是这个下场（plan_id 一直是空的）。</p>
     *
     * <p>挂到 afterCommit 之后，租户行已经落库可见，免费版与限流配额才会真的写进去。</p>
     */
    private void startTrialAfterCommit(String tenantCode) {
        Integer trialDays = registrationPolicyClient.trialDays();
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            trialClient.startFreeTrial(tenantCode, trialDays);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                trialClient.startFreeTrial(tenantCode, trialDays);
            }
        });
    }

    @Transactional
    public ReviewPageVO reject(LoginUser operator, long reviewId, String reason) {
        long reviewerId = operator.userId();
        String safeReason = reason == null || reason.isBlank() ? "资料核验未通过" : reason.trim();
        ReviewPageVO vo = detail(reviewId);
        if (vo.getStatus() != 1) {
            throw new BizException(40001, "仅待审核记录可执行驳回操作");
        }

        LambdaUpdateWrapper<EnterpriseReview> reviewUpdate = Wrappers.lambdaUpdate(EnterpriseReview.class)
                .eq(EnterpriseReview::getId, reviewId)
                .set(EnterpriseReview::getStatus, 3)
                .set(EnterpriseReview::getReviewerId, reviewerId)
                .set(EnterpriseReview::getReviewTime, LocalDateTime.now())
                .set(EnterpriseReview::getRejectReason, safeReason)
                .set(EnterpriseReview::getUpdateTime, LocalDateTime.now());
        reviewMapper.update(null, reviewUpdate);

        LambdaUpdateWrapper<Enterprise> enterpriseUpdate = Wrappers.lambdaUpdate(Enterprise.class)
                .eq(Enterprise::getId, Long.parseLong(vo.getEnterpriseId()))
                .eq(Enterprise::getDeleted, false)
                .set(Enterprise::getStatus, 3)
                .set(Enterprise::getEditor, String.valueOf(reviewerId))
                .set(Enterprise::getUpdateTime, LocalDateTime.now());
        enterpriseMapper.update(null, enterpriseUpdate);
        auditLogClient.record(operator, AUDIT_MODULE, "驳回企业审核", "企业", vo.getApplyNo(), true,
                "驳回「" + vo.getCompanyName() + "」的申请，原因：" + safeReason);
        return detail(reviewId);
    }

    private ReviewPageVO toVO(EnterpriseReview review, Enterprise enterprise) {
        ReviewPageVO vo = new ReviewPageVO();
        vo.setId(String.valueOf(review.getId()));
        vo.setEnterpriseId(String.valueOf(enterprise.getId()));
        vo.setEnterpriseCode(enterprise.getEnterpriseCode());
        vo.setTenantCode(enterprise.getTenantCode());
        vo.setApplyNo(review.getApplyNo());
        vo.setVersionNo(review.getVersionNo());
        vo.setApplicantId(review.getApplicantId() == null ? null : String.valueOf(review.getApplicantId()));
        vo.setCompanyName(review.getCompanyName());
        vo.setIndustry(review.getIndustry());
        vo.setScale(review.getScale());
        vo.setContactName(review.getContactName());
        vo.setContactPhone(review.getContactPhone());
        vo.setContactEmail(review.getContactEmail());
        vo.setLicenseNo(review.getLicenseNo());
        vo.setRegisterAddress(review.getRegisterAddress());
        vo.setLegalPerson(review.getLegalPerson());
        vo.setLicenseFileId(review.getLicenseFileId() == null ? null : String.valueOf(review.getLicenseFileId()));
        vo.setStatus(review.getStatus());
        vo.setRejectReason(review.getRejectReason());
        vo.setReviewerId(review.getReviewerId() == null ? null : String.valueOf(review.getReviewerId()));
        vo.setReviewTime(review.getReviewTime());
        vo.setSubmitTime(review.getSubmitTime());
        vo.setEnterpriseStatus(enterprise.getStatus());
        return vo;
    }

    private String generateTenantCode() {
        String date = CODE_DATE.format(LocalDate.now());
        String prefix = "T" + date;
        String latest = tenantMapper.findLatestCodeByDatePrefix(prefix);
        long base = latest == null ? 0 : parseSeq(latest, prefix);
        long seq = Math.max(base + 1, DAY_SEQ.incrementAndGet());
        return prefix + String.format("%07d", seq);
    }

    private long parseSeq(String code, String prefix) {
        try {
            return Long.parseLong(code.substring(prefix.length()));
        } catch (Exception e) {
            return 0;
        }
    }
}
