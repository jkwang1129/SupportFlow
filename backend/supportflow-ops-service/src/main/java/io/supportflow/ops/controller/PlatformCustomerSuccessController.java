package io.supportflow.ops.controller;

import io.supportflow.common.context.LoginUserContext;
import io.supportflow.common.api.ApiResponse;
import io.supportflow.common.api.IdVO;
import io.supportflow.common.auth.LoginUser;
import io.supportflow.common.exception.BizException;
import io.supportflow.ops.dto.FinishBody;
import io.supportflow.ops.dto.FollowUpBody;
import io.supportflow.ops.dto.FollowUpVO;
import io.supportflow.ops.dto.TaskBody;
import io.supportflow.ops.dto.TaskFinishVO;
import io.supportflow.ops.dto.TaskPageVO;
import io.supportflow.ops.service.CustomerSuccessService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台「客户成功」的人维护部分：随访安排（谁负责、下次回访、人工覆盖的风险与商机）
 * + 回访任务（建 / 改 / 完成 / 取消）。
 *
 * <p>健康度、续费风险、扩展商机的自动判断在 tenant-service；这里只放"运营决定的事"。
 * 页面把两边的数据按租户编号合并展示。</p>
 */
@RestController
@RequestMapping("/api/ops/platform/customer-success")
public class PlatformCustomerSuccessController {

    private final CustomerSuccessService customerSuccessService;

    public PlatformCustomerSuccessController(CustomerSuccessService customerSuccessService) {
        this.customerSuccessService = customerSuccessService;
    }

    /** 随访安排（一租户一行） */
    @GetMapping("/follow-ups")
    public ApiResponse<FollowUpVO> followUps() {
        requirePlatform();
        return ApiResponse.ok(customerSuccessService.followUps());
    }

    /** 保存随访安排（upsert，并留审计） */
    @PutMapping("/follow-ups")
    public ApiResponse<FollowUpVO> saveFollowUp(
            @RequestBody FollowUpBody body
    ) {
        LoginUser operator = requirePlatform();
        return ApiResponse.ok(customerSuccessService.saveFollowUp(operator, body));
    }

    /** 回访任务列表（可按状态 / 租户筛） */
    @GetMapping("/tasks")
    public ApiResponse<TaskPageVO> tasks(
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String tenantCode,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize
    ) {
        requirePlatform();
        return ApiResponse.ok(customerSuccessService.tasks(status, tenantCode, page, pageSize));
    }

    /** 新建回访任务 */
    @PostMapping("/tasks")
    public ApiResponse<IdVO> createTask(
            @RequestBody TaskBody body
    ) {
        LoginUser operator = requirePlatform();
        return ApiResponse.ok(customerSuccessService.createTask(operator, body));
    }

    /** 修改回访任务 */
    @PutMapping("/tasks/{id}")
    public ApiResponse<IdVO> updateTask(
            @PathVariable long id,
            @RequestBody TaskBody body
    ) {
        LoginUser operator = requirePlatform();
        return ApiResponse.ok(customerSuccessService.updateTask(operator, id, body));
    }

    /** 完成 / 取消回访任务（完成时写回访结论，并更新随访状态里的"上次回访时间"） */
    @PostMapping("/tasks/{id}/finish")
    public ApiResponse<TaskFinishVO> finishTask(
            @PathVariable long id,
            @RequestBody(required = false) FinishBody body
    ) {
        LoginUser operator = requirePlatform();
        return ApiResponse.ok(customerSuccessService.finishTask(operator, id, body));
    }

    private LoginUser requirePlatform() {
        LoginUser user = LoginUserContext.require();
        if (user.userType() != 1) {
            throw new BizException(40302, "只有平台账号能用客户成功");
        }
        return user;
    }
}
