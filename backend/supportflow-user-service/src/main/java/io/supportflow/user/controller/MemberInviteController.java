package io.supportflow.user.controller;

import io.supportflow.common.context.LoginUserContext;
import io.supportflow.common.api.ApiResponse;
import io.supportflow.common.auth.LoginUser;
import io.supportflow.user.dto.AcceptInviteRequest;
import io.supportflow.user.dto.AcceptResult;
import io.supportflow.user.dto.ColleagueVO;
import io.supportflow.user.dto.CreateInviteRequest;
import io.supportflow.user.dto.CreateInviteResult;
import io.supportflow.user.dto.InvitePreviewVO;
import io.supportflow.user.dto.InviteVO;
import io.supportflow.user.dto.MemberAccessVO;
import io.supportflow.user.dto.MemberVO;
import io.supportflow.user.dto.RoleOption;
import io.supportflow.user.service.MemberInviteService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 企业客服成员：成员列表 / 邀请管理 / 公共接受邀请。
 */
@RestController
@RequestMapping("/api/user/members")
public class MemberInviteController {

    private final MemberInviteService memberService;

    public MemberInviteController(MemberInviteService memberService) {
        this.memberService = memberService;
    }

    /**
     * 可邀请角色。
     */
    @GetMapping("/roles")
    public ApiResponse<List<RoleOption>> roles() {
        return ApiResponse.ok(memberService.roleOptions());
    }

    /**
     * 同事列表（转接会话选人用，任何企业成员都能看）。
     */
    @GetMapping("/colleagues")
    public ApiResponse<List<ColleagueVO>> colleagues() {
        return ApiResponse.ok(memberService.colleagues(requireEnterpriseLogin()));
    }

    /**
     * 当前企业成员访问信息（是否可管理成员 + 当前角色）。
     */
    @GetMapping("/access")
    public ApiResponse<MemberAccessVO> access() {
        LoginUser user = requireEnterpriseLogin();
        return ApiResponse.ok(memberService.memberAccess(user));
    }

    /**
     * 企业成员列表。
     */
    @GetMapping
    public ApiResponse<List<MemberVO>> members() {
        LoginUser user = requireManager();
        return ApiResponse.ok(memberService.listMembers(user));
    }

    /**
     * 邀请记录列表。
     */
    @GetMapping("/invites")
    public ApiResponse<List<InviteVO>> invites() {
        LoginUser user = requireManager();
        return ApiResponse.ok(memberService.listInvites(user));
    }

    /**
     * 创建邀请。
     */
    @PostMapping("/invites")
    public ApiResponse<CreateInviteResult> createInvite(
            @Valid @RequestBody CreateInviteRequest request
    ) {
        LoginUser user = requireManager();
        return ApiResponse.ok(memberService.create(
                user,
                request.phone(),
                request.email(),
                request.roleCode(),
                request.expireDays()
        ));
    }

    /**
     * 重新发送邀请邮件。
     */
    @PostMapping("/invites/{inviteCode}/resend")
    public ApiResponse<Boolean> resend(
            @PathVariable String inviteCode
    ) {
        LoginUser user = requireManager();
        return ApiResponse.ok(memberService.resend(user, inviteCode));
    }

    /**
     * 撤销邀请。
     */
    @PostMapping("/invites/{inviteCode}/revoke")
    public ApiResponse<Void> revoke(
            @PathVariable String inviteCode
    ) {
        LoginUser user = requireManager();
        memberService.revoke(user, inviteCode);
        return ApiResponse.ok();
    }

    /**
     * 公共邀请预览（被邀请人打开链接时调用）。
     */
    @GetMapping("/invites/{code}/preview")
    public ApiResponse<InvitePreviewVO> preview(@PathVariable String code) {
        return ApiResponse.ok(memberService.preview(code));
    }

    /**
     * 公共接受邀请（创建企业账号并加入团队）。
     */
    @PostMapping("/invites/{code}/accept")
    public ApiResponse<AcceptResult> accept(
            @PathVariable String code,
            @Valid @RequestBody AcceptInviteRequest request
    ) {
        return ApiResponse.ok(memberService.accept(
                code,
                request.name(),
                request.password(),
                request.phone(),
                request.email()
        ));
    }

    private LoginUser requireEnterpriseLogin() {
        LoginUser loginUser = LoginUserContext.require();
        memberService.requireEnterpriseMember(loginUser);
        return loginUser;
    }

    private LoginUser requireManager() {
        LoginUser loginUser = LoginUserContext.require();
        memberService.requireManager(loginUser);
        return loginUser;
    }
}
