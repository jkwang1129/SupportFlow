package io.supportflow.customer.security;

import io.supportflow.common.auth.AuthState;
import io.supportflow.common.auth.LoginUser;
import io.supportflow.common.context.LoginUserContext;
import io.supportflow.common.context.TenantContext;
import io.supportflow.common.exception.BizException;
import io.supportflow.customer.config.FileStorageProperties;
import io.supportflow.customer.entity.FileMeta;
import io.supportflow.customer.mapper.FileMetaMapper;
import io.supportflow.customer.service.license.EnterpriseLicenseFileService;
import io.supportflow.customer.storage.ObjectStorage;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LicensePrivilegeTest {
    private final LoginUser owner = new LoginUser(99, "u", "Owner", 2, "");
    private final LoginUser reviewer = new LoginUser(10, "p", "Reviewer", 1, "PLATFORM");

    @Test void fileScopeIsStableAfterApprovalAndCannotReadAnotherUploader() throws Exception {
        var storage = mock(ObjectStorage.class);
        var mapper = mock(FileMetaMapper.class);
        var service = new EnterpriseLicenseFileService(storage, mapper, new FileStorageProperties(), null);
        assertEquals("L2r", CustomerPrivilegeFilter.licenseScope(owner));
        assertEquals("L2r", CustomerPrivilegeFilter.licenseScope(new LoginUser(99,"u","Owner",2,"TENANT_A")));
        assertThrows(BizException.class, () -> CustomerPrivilegeFilter.licenseScope(reviewer));
        when(mapper.selectOne(any())).thenReturn(FileMeta.builder().id(1L).tenantCode("L2r")
                .creator("100").bizType(2).build());
        TenantContext.bind("L2r", () -> {
            assertThrows(BizException.class, () -> service.loadLicense(owner,1));
            return null;
        });
        verifyNoInteractions(storage);
        assertThrows(BizException.class, () -> service.loadLicense(owner,1));
        assertThrows(BizException.class, () -> service.loadLicense(reviewer,1));
    }

    @Test void filterBindsBeforeTransactionAndDoesNotTrustTenantHeaders() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var filter = new CustomerPrivilegeFilter(jdbc);
        var request = request("/api/customer/files/enterprise/1");
        request.addHeader("X-Tenant-Code", "PLATFORM");
        LoginUserContext.bind(AuthState.ok(owner), () -> {
            filter.doFilter(request,new MockHttpServletResponse(),(req,res) -> {
                assertEquals("L2r",TenantContext.get()); assertFalse(PlatformAccess.active());
            }); return null;
        });
        assertNull(TenantContext.get()); assertFalse(PlatformAccess.active());
        verifyNoInteractions(jdbc);
    }

    @Test void platformScopeRequiresPlatformIdentityAndMandatoryAudit() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var filter = new CustomerPrivilegeFilter(jdbc);
        LoginUserContext.bind(AuthState.ok(owner), () -> {
            var response = new MockHttpServletResponse();
            filter.doFilter(request("/api/platform/tickets"),response,(req,res) -> fail("unauthorized"));
            assertEquals(403,response.getStatus()); return null;
        });
        LoginUserContext.bind(AuthState.ok(reviewer), () -> {
            filter.doFilter(request("/api/customer/files/enterprise/1"),new MockHttpServletResponse(),
                    (req,res) -> assertTrue(PlatformAccess.active())); return null;
        });
        verify(jdbc,times(2)).update(anyString(),eq(10L),anyString(),anyString(),anyInt());
        assertFalse(PlatformAccess.active());
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("audit unavailable"))
                .when(jdbc).update(anyString(),any(Object[].class));
        LoginUserContext.bind(AuthState.ok(reviewer), () -> {
            assertThrows(RuntimeException.class, () -> filter.doFilter(request("/api/platform/tickets"),
                    new MockHttpServletResponse(),(req,res) -> fail("audit failure must block")));
            return null;
        });
    }
    private MockHttpServletRequest request(String path) {
        var request = new MockHttpServletRequest("GET",path); request.setServletPath(path); return request;
    }
}
