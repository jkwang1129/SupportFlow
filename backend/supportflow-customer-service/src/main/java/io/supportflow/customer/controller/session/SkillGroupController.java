package io.supportflow.customer.controller.session;

import io.supportflow.common.context.LoginUserContext;
import io.supportflow.common.api.ApiResponse;
import io.supportflow.common.auth.LoginUser;
import io.supportflow.customer.dto.session.BindBody;
import io.supportflow.customer.dto.session.CreateBody;
import io.supportflow.customer.dto.session.GroupVO;
import io.supportflow.customer.dto.session.MemberBody;
import io.supportflow.customer.dto.session.SkillGroupUpdateBody;
import io.supportflow.customer.service.session.SkillGroupService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 技能组：管理组、组内坐席、以及"渠道绑定哪个组"。
 */
@RestController
@RequestMapping("/api/customer/skill-groups")
public class SkillGroupController {

    private final SkillGroupService skillGroupService;

    public SkillGroupController(SkillGroupService skillGroupService) {
        this.skillGroupService = skillGroupService;
    }

    /**
     * 技能组列表（含成员与绑定的渠道）。
     */
    @GetMapping
    public ApiResponse<List<GroupVO>> list() {
        return ApiResponse.ok(skillGroupService.list(LoginUserContext.require()));
    }

    /**
     * 新建技能组。
     */
    @PostMapping
    public ApiResponse<GroupVO> create(
            @Valid @RequestBody CreateBody body
    ) {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(skillGroupService.create(
                user, body.name(), body.description(), body.overflowAfterSeconds()));
    }

    /**
     * 修改技能组（名称 / 描述 / 启用状态）。
     */
    @PutMapping("/{groupId}")
    public ApiResponse<GroupVO> update(
            @PathVariable long groupId,
            @Valid @RequestBody SkillGroupUpdateBody body
    ) {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(skillGroupService.update(
                user, groupId, body.description(), body.overflowAfterSeconds()));
    }

    /**
     * 设置组内坐席（覆盖式：前端传最终想要的成员列表）。
     */
    @PutMapping("/{groupId}/members")
    public ApiResponse<GroupVO> setMembers(
            @PathVariable long groupId,
            @RequestBody MemberBody body
    ) {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(skillGroupService.setMembers(user, groupId, body.userIds()));
    }

    /**
     * 渠道绑定技能组（skillGroupId 传 null 表示解绑）。
     */
    @PostMapping("/bind-channel")
    public ApiResponse<Void> bindChannel(
            @Valid @RequestBody BindBody body
    ) {
        LoginUser user = LoginUserContext.require();
        skillGroupService.bindChannel(user, body.channelId(), body.skillGroupId());
        return ApiResponse.ok(null);
    }

    /**
     * 删除技能组（默认组不允许删）。
     */
    @DeleteMapping("/{groupId}")
    public ApiResponse<Void> remove(
            @PathVariable long groupId
    ) {
        LoginUser user = LoginUserContext.require();
        skillGroupService.remove(user, groupId);
        return ApiResponse.ok(null);
    }
}
