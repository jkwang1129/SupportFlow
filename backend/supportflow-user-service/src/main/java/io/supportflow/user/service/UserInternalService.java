package io.supportflow.user.service;

import io.supportflow.user.dto.UserNameVO;
import io.supportflow.user.entity.SysUser;
import io.supportflow.user.mapper.SysUserMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * 服务间内部用户查询/回填：不经过网关对外开放，只给 tenant / customer 等内部服务用。
 *
 * <p>把 SQL 与参数解析放在这里，内部控制器就只剩"接请求、包响应"，
 * 后面加字段或改口径不会牵动接口签名。</p>
 */
@Service
public class UserInternalService {

    private final SysUserMapper sysUserMapper;

    public UserInternalService(SysUserMapper sysUserMapper) {
        this.sysUserMapper = sysUserMapper;
    }

    /** 回填用户主归属租户编码（tenant-service 审核通过后调用）。 */
    public void updateTenantCode(long userId, String tenantCode) {
        LambdaUpdateWrapper<SysUser> update = Wrappers.lambdaUpdate(SysUser.class)
                .eq(SysUser::getId, userId)
                .eq(SysUser::getDeleted, false)
                .set(SysUser::getTenantCode, tenantCode)
                .set(SysUser::getUpdateTime, LocalDateTime.now());
        sysUserMapper.update(null, update);
    }

    /**
     * 批量查用户姓名（跨服务展示"创建人"用）。
     *
     * <p>入参是逗号分隔的 id 串（内部接口的既成约定），这里负责拆串、去重、
     * 空串短路，控制器只做转发。</p>
     */
    public List<UserNameVO> userNames(String ids) {
        List<Long> userIds = Arrays.stream(ids.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(Long::parseLong)
                .distinct()
                .toList();
        if (userIds.isEmpty()) {
            return List.of();
        }
        return sysUserMapper.selectBatchIds(userIds).stream()
                .map(u -> new UserNameVO(String.valueOf(u.getId()),
                        u.getName() == null ? "" : u.getName()))
                .toList();
    }
}
