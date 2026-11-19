package io.supportflow.tenant.service;

import io.supportflow.common.exception.BizException;
import io.supportflow.common.auth.LoginUser;
import io.supportflow.common.id.SnowflakeIdGenerator;
import io.supportflow.tenant.admin.service.EnterpriseReviewAdminService;
import io.supportflow.tenant.dto.GuideState;
import io.supportflow.tenant.dto.ProfileRequest;
import io.supportflow.tenant.entity.Enterprise;
import io.supportflow.tenant.entity.EnterpriseReview;
import io.supportflow.tenant.internal.RegistrationPolicyClient;
import io.supportflow.tenant.mapper.EnterpriseMapper;
import io.supportflow.tenant.mapper.EnterpriseReviewMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 企业开通引导服务：完善资料 → 提交审核 → 审核通过创建租户。
 */
@Service
public class EnterpriseOnboardingService {

    private static final Logger log = LoggerFactory.getLogger(EnterpriseOnboardingService.class);

    /** 引导阶段：待完善资料 / 审核中 / 已驳回 / 已通过 */
    public static final String STAGE_PENDING_PROFILE = "PENDING_PROFILE";
    public static final String STAGE_PENDING_REVIEW = "PENDING_REVIEW";
    public static final String STAGE_REJECTED = "REJECTED";
    public static final String STAGE_APPROVED = "APPROVED";

    private static final DateTimeFormatter APPLY_NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final EnterpriseMapper enterpriseMapper;
    private final EnterpriseReviewMapper enterpriseReviewMapper;
    private final SnowflakeIdGenerator idGenerator;
    /** 注册审核策略：提交资料时判断能不能直接放行（关掉人工审核 / 邮箱域名命中白名单） */
    private final RegistrationPolicyClient registrationPolicyClient;
    /** 自动放行走人工审批的同一段逻辑（同一条审计、同样的试用开通） */
    private final EnterpriseReviewAdminService reviewAdminService;
    private final io.supportflow.tenant.internal.LicenseOwnershipClient licenseOwnership;

    public EnterpriseOnboardingService(
            EnterpriseMapper enterpriseMapper,
            EnterpriseReviewMapper enterpriseReviewMapper,
            SnowflakeIdGenerator idGenerator,
            RegistrationPolicyClient registrationPolicyClient,
            EnterpriseReviewAdminService reviewAdminService,
            io.supportflow.tenant.internal.LicenseOwnershipClient licenseOwnership
    ) {
        this.enterpriseMapper = enterpriseMapper;
        this.enterpriseReviewMapper = enterpriseReviewMapper;
        this.idGenerator = idGenerator;
        this.registrationPolicyClient = registrationPolicyClient;
        this.reviewAdminService = reviewAdminService;
        this.licenseOwnership = licenseOwnership;
    }

    /**
     * 获取当前企业开通引导状态。
     */
    @Transactional(readOnly = true)
    public GuideState getState(LoginUser user) {
        Enterprise enterprise = requireEnterprise(user);
        Optional<EnterpriseReview> reviewOpt = Optional.ofNullable(
                enterpriseReviewMapper.findLatestByEnterpriseId(enterprise.getId()));

        if (reviewOpt.isEmpty()) {
            return toState(enterprise, null, STAGE_PENDING_PROFILE);
        }
        EnterpriseReview review = reviewOpt.get();
        String stage = switch (review.getStatus()) {
            case 1 -> STAGE_PENDING_REVIEW;
            case 3 -> STAGE_REJECTED;
            default -> STAGE_APPROVED;
        };
        if (enterprise.getStatus() != null && enterprise.getStatus() == 2) {
            stage = STAGE_APPROVED;
        }
        return toState(enterprise, review, stage);
    }

    /**
     * 保存企业资料（不提交审核，供中途暂存）。
     */
    @Transactional
    public GuideState saveProfile(LoginUser user, ProfileRequest request) {
        licenseOwnership.requireOwner(user.userId(), request.licenseFileId());
        Enterprise enterprise = requireEnterprise(user);
        updateEnterpriseProfile(enterprise.getId(), request);
        return getState(user);
    }

    /**
     * 提交企业资料进入审核（可驳回后重提，版本号递增）。
     */
    @Transactional
    public GuideState submitReview(LoginUser user, ProfileRequest request) {
        licenseOwnership.requireOwner(user.userId(), request.licenseFileId());
        long applicantId = user.userId();
        Enterprise enterprise = requireEnterprise(user);
        if (enterprise.getStatus() != null && enterprise.getStatus() == 2) {
            throw new BizException(40001, "企业已开通，无需重复提交审核");
        }

        long reviewId = idGenerator.nextId();
        int versionNo = enterpriseReviewMapper.nextVersionNo(enterprise.getId());
        String applyNo = "YR" + APPLY_NO_TIME.format(LocalDateTime.now())
                + ThreadLocalRandom.current().nextInt(1000, 10000);

        EnterpriseReview review = EnterpriseReview.builder()
                .id(reviewId)
                .enterpriseId(enterprise.getId())
                .applyNo(applyNo)
                .versionNo(versionNo)
                .applicantId(applicantId)
                .companyName(request.companyName())
                .industry(request.industry())
                .scale(request.scale())
                .contactName(request.contactName())
                .contactPhone(request.contactPhone())
                .contactEmail(request.contactEmail())
                .licenseNo(request.licenseNo())
                .registerAddress(request.registerAddress())
                .legalPerson(request.legalPerson())
                .licenseFileId(request.licenseFileId())
                .status(1)
                .submitTime(LocalDateTime.now())
                .creator(String.valueOf(applicantId))
                .deleted(false)
                .build();
        enterpriseReviewMapper.insert(review);

        // 被驳回后重新提交：企业状态恢复为待审核
        if (enterprise.getStatus() != null && enterprise.getStatus() == 3) {
            LambdaUpdateWrapper<Enterprise> reset = Wrappers.lambdaUpdate(Enterprise.class)
                    .eq(Enterprise::getId, enterprise.getId())
                    .eq(Enterprise::getDeleted, false)
                    .set(Enterprise::getStatus, 1)
                    .set(Enterprise::getUpdateTime, LocalDateTime.now());
            enterpriseMapper.update(null, reset);
        }

        // 企业主数据同步为最新提交资料
        updateEnterpriseProfile(enterprise.getId(), request);

        // 注册审核策略的执行点②③：平台关掉「需运营审核」，或企业邮箱域名命中白名单
        // → 提交那一刻就开通，不进人工队列（走人工审批的同一段逻辑，审计写明命中哪条策略）
        String autoReason = autoApproveReason(request.contactEmail());
        if (autoReason != null) {
            reviewAdminService.autoApprove(reviewId, autoReason);
            log.info("企业资料提交即自动放行 reviewId={} enterpriseId={} reason={}",
                    reviewId, enterprise.getId(), autoReason);
        }
        return getState(user);
    }

    /**
     * 自动放行判定：命中平台策略才直通，其余照旧进人工审核队列。
     *
     * <p>顺序有意这么排：先看"关掉人工审核"（运营的原意就是全部自动过），再看白名单域名；
     * 都不命中返回 null = 老老实实排人工。策略读不到时 {@link RegistrationPolicyClient}
     * 回的是保守值（需审核 + 白名单为空），所以 ops 挂了不会把谁误放进来。</p>
     */
    private String autoApproveReason(String contactEmail) {
        if (!registrationPolicyClient.needReview()) {
            return "平台已关闭「新注册企业需运营审核」";
        }
        if (registrationPolicyClient.domainAutoApproved(contactEmail)) {
            return "企业邮箱域名命中平台白名单";
        }
        return null;
    }

    /**
     * 定位"当前用户所属的企业"。
     *
     * <p>两种身份两种情况：</p>
     * <ul>
     *   <li>已经归属某个租户（企业已开通后的管理员/员工）：按 <b>tenant_code</b> 找企业。
     *       企业开通后租户里会有多个成员，如果只按"当初注册的那个人"找，别的成员进
     *       「我的企业」就会被判成"账号未关联企业资料"；</li>
     *   <li>还没归属租户（正在注册 / 完善资料 / 等审核，tenant_code 是 PLATFORM）：
     *       按申请人 ID 找自己那条草稿，保持原来的开通引导流程不变。</li>
     * </ul>
     */
    private Enterprise requireEnterprise(LoginUser user) {
        String tenantCode = user.tenantCode();
        Enterprise enterprise = null;
        if (tenantCode != null && !tenantCode.isBlank() && !"PLATFORM".equalsIgnoreCase(tenantCode)) {
            enterprise = enterpriseMapper.findByTenantCode(tenantCode);
        }
        if (enterprise == null) {
            enterprise = enterpriseMapper.findByApplicantId(String.valueOf(user.userId()));
        }
        if (enterprise == null) {
            throw new BizException(40401, "账号未关联企业资料，请通过注册流程开通企业");
        }
        return enterprise;
    }

    private void updateEnterpriseProfile(long enterpriseId, ProfileRequest request) {
        LambdaUpdateWrapper<Enterprise> update = Wrappers.lambdaUpdate(Enterprise.class)
                .eq(Enterprise::getId, enterpriseId)
                .eq(Enterprise::getDeleted, false)
                .set(Enterprise::getCompanyName, request.companyName())
                .set(Enterprise::getIndustry, request.industry())
                .set(Enterprise::getScale, request.scale())
                .set(Enterprise::getLicenseNo, request.licenseNo())
                .set(Enterprise::getRegisterAddress, request.registerAddress())
                .set(Enterprise::getLegalPerson, request.legalPerson())
                .set(Enterprise::getLicenseFileId, request.licenseFileId())
                .set(Enterprise::getContactName, request.contactName())
                .set(Enterprise::getContactPhone, request.contactPhone())
                .set(Enterprise::getContactEmail, request.contactEmail())
                .set(Enterprise::getEditor, "SELF_SERVICE")
                .set(Enterprise::getUpdateTime, LocalDateTime.now());
        enterpriseMapper.update(null, update);
    }

    private GuideState toState(
            Enterprise enterprise,
            EnterpriseReview review,
            String stage
    ) {
        return new GuideState(
                enterprise.getId(),
                enterprise.getEnterpriseCode(),
                stage,
                enterprise.getCompanyName(),
                enterprise.getIndustry(),
                enterprise.getScale(),
                enterprise.getLicenseNo(),
                enterprise.getRegisterAddress(),
                enterprise.getLegalPerson(),
                enterprise.getLicenseFileId() == null ? null : String.valueOf(enterprise.getLicenseFileId()),
                enterprise.getContactName(),
                enterprise.getContactPhone(),
                enterprise.getContactEmail(),
                review == null ? null : review.getApplyNo(),
                review == null ? null : review.getVersionNo(),
                review == null ? null : review.getRejectReason(),
                review == null || review.getSubmitTime() == null
                        ? null
                        : review.getSubmitTime().toString(),
                enterprise.getTenantCode(),
                // 审核记录通过但没有人工审核人 = 自动放行（前端据此说明"为什么没等就过了"）
                review != null && review.getStatus() != null && review.getStatus() == 2
                        && review.getReviewerId() == null
        );
    }
}
