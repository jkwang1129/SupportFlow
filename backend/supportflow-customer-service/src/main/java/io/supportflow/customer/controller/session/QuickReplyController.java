package io.supportflow.customer.controller.session;

import io.supportflow.common.context.LoginUserContext;
import io.supportflow.common.api.ApiResponse;
import io.supportflow.common.auth.LoginUser;
import io.supportflow.customer.dto.session.QuickReplyBody;
import io.supportflow.customer.dto.session.QuickReplySaveVO;
import io.supportflow.customer.dto.session.QuickReplyVO;
import io.supportflow.customer.service.session.QuickReplyService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 快捷话术（坐席工作台 Alt+1~9）。
 *
 * <p>企业侧接口：坐席读「启用中的话术」（带 Alt 序号），有管理权限的人在设置里增删改。
 * 话术按使用次数倒序排，最常发的自然落到 Alt+1。</p>
 */
@RestController
@RequestMapping("/api/customer/quick-replies")
public class QuickReplyController {

    private final QuickReplyService quickReplyService;

    public QuickReplyController(QuickReplyService quickReplyService) {
        this.quickReplyService = quickReplyService;
    }

    /**
     * 话术列表：onlyEnabled=true 给坐席工作台用（只回启用中的，带 Alt 序号）
     */
    @GetMapping
    public ApiResponse<List<QuickReplyVO>> list(
            @RequestParam(defaultValue = "false") boolean onlyEnabled) {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(quickReplyService.list(user.tenantCode(), onlyEnabled));
    }

    /**
     * 新建 / 修改（id 为空是新建）
     */
    @PostMapping
    public ApiResponse<QuickReplySaveVO> save(
            @RequestBody QuickReplyBody body) {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(quickReplyService.save(user, null, body));
    }

    /**
     * 修改已有话术（id 由路径给）。
     */
    @PutMapping("/{id}")
    public ApiResponse<QuickReplySaveVO> update(
            @PathVariable long id,
            @RequestBody QuickReplyBody body) {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(quickReplyService.save(user, id, body));
    }

    /**
     * 删除话术（逻辑删除）。
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> remove(
            @PathVariable long id) {
        LoginUser user = LoginUserContext.require();
        quickReplyService.remove(user, id);
        return ApiResponse.ok();
    }

    /**
     * 坐席用了一次：累加使用次数（工作台点 Alt+N 或点面板插入时调用）
     */
    @PostMapping("/{id}/use")
    public ApiResponse<List<QuickReplyVO>> use(
            @PathVariable long id) {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(quickReplyService.use(user.tenantCode(), id));
    }

    /**
     * 一键预置常用话术（幂等：已有话术就不动）
     */
    @PostMapping("/init-defaults")
    public ApiResponse<List<QuickReplyVO>> initDefaults() {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(quickReplyService.initDefaults(user));
    }
}
