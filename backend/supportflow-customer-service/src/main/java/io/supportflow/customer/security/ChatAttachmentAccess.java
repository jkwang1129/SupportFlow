package io.supportflow.customer.security;

import io.supportflow.common.auth.LoginUser;
import io.supportflow.common.context.LoginUserContext;
import io.supportflow.common.exception.BizException;
import org.springframework.stereotype.Component;

/** A channel key is public; private chat media needs a session or enterprise identity. */
@Component
public class ChatAttachmentAccess {
    private final VisitorTokenService visitors;

    public ChatAttachmentAccess(VisitorTokenService visitors) {
        this.visitors = visitors;
    }

    public String tenant(String sessionNo, String visitorToken) {
        if (visitorToken != null && !visitorToken.isBlank()) {
            var visitor = visitors.parseToken(visitorToken);
            if (!sessionNo.equals(visitor.sessionNo())) {
                throw new BizException(40301, "图片不属于当前访客会话");
            }
            return visitor.tenantCode();
        }
        LoginUser user = LoginUserContext.require();
        if (user.userType() != 2 || user.tenantCode() == null || user.tenantCode().isBlank()
                || "PLATFORM".equalsIgnoreCase(user.tenantCode())) {
            throw new BizException(40301, "请使用会话访客身份或企业成员身份");
        }
        return user.tenantCode();
    }
}
