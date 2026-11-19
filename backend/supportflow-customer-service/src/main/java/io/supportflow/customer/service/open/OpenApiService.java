package io.supportflow.customer.service.open;

import io.supportflow.common.exception.BizException;
import io.supportflow.common.context.TenantContext;
import io.supportflow.customer.dto.open.OpenApiOverviewVO;
import io.supportflow.customer.entity.Customer;
import io.supportflow.customer.entity.Session;
import io.supportflow.customer.entity.Ticket;
import io.supportflow.customer.internal.SecurityOpsClient;
import io.supportflow.customer.mapper.CustomerMapper;
import io.supportflow.customer.mapper.SessionMapper;
import io.supportflow.customer.mapper.TicketMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 开放 API：企业自有系统用 API 密钥调用（不需要人登录后台）。
 *
 * <p><b>第一版只开一个只读聚合接口</b>：出的是"多少条"这类统计，不出明细。
 * 对外开放的接口每多一个就多一处越权风险，宁可先窄后宽。</p>
 */
@Service
public class OpenApiService {

    private static final Logger log = LoggerFactory.getLogger(OpenApiService.class);

    private final SecurityOpsClient securityOpsClient;
    private final SessionMapper sessionMapper;
    private final TicketMapper ticketMapper;
    private final CustomerMapper customerMapper;

    public OpenApiService(SecurityOpsClient securityOpsClient,
                          SessionMapper sessionMapper,
                          TicketMapper ticketMapper,
                          CustomerMapper customerMapper) {
        this.securityOpsClient = securityOpsClient;
        this.sessionMapper = sessionMapper;
        this.ticketMapper = ticketMapper;
        this.customerMapper = customerMapper;
    }

    /**
     * 租户运营概览（只读聚合）。
     *
     * <p>密钥缺失 / 失效 / 过期 / 被停用一律 401；校验失败也按拒绝处理——
     * 开放接口宁可 502，也不能放过一个未鉴权的请求。</p>
     *
     * <p>鉴权（拿到 tenantCode）与三次 count 都属于业务，放这里；控制器只收请求头、包响应。</p>
     */
    public OpenApiOverviewVO overview(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new BizException(40101, "缺少 X-Api-Key 请求头");
        }
        String tenantCode = securityOpsClient.verifyApiKey(apiKey.trim());
        if (tenantCode == null || tenantCode.isBlank()) {
            throw new BizException(40101, "API 密钥无效、已停用或已过期");
        }

        return TenantContext.bind(tenantCode, () -> overviewForTenant(tenantCode));
    }

    private OpenApiOverviewVO overviewForTenant(String tenantCode) {
        long sessions = sessionMapper.selectCount(Wrappers.<Session>lambdaQuery()
                .eq(Session::getTenantCode, tenantCode)
                .eq(Session::getDeleted, false));
        long tickets = ticketMapper.selectCount(Wrappers.<Ticket>lambdaQuery()
                .eq(Ticket::getTenantCode, tenantCode)
                .eq(Ticket::getDeleted, false));
        long customers = customerMapper.selectCount(Wrappers.<Customer>lambdaQuery()
                .eq(Customer::getTenantCode, tenantCode)
                .eq(Customer::getDeleted, false));

        log.info("开放接口调用 tenant={} sessions={} tickets={} customers={}",
                tenantCode, sessions, tickets, customers);
        return new OpenApiOverviewVO(tenantCode, sessions, tickets, customers,
                LocalDateTime.now().withNano(0).toString().replace('T', ' '));
    }
}
