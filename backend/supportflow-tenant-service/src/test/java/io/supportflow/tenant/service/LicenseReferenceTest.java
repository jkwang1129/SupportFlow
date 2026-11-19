package io.supportflow.tenant.service;

import io.supportflow.common.auth.LoginUser;
import io.supportflow.common.exception.BizException;
import io.supportflow.tenant.internal.LicenseOwnershipClient;
import io.supportflow.tenant.mapper.EnterpriseMapper;
import io.supportflow.tenant.mapper.EnterpriseReviewMapper;
import io.supportflow.tenant.dto.ProfileRequest;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class LicenseReferenceTest {
    @Test void foreignOrUnavailableAttachmentBlocksBothWritesBeforeDatabaseMutation() {
        var ownership = mock(LicenseOwnershipClient.class);
        var enterprises = mock(EnterpriseMapper.class);
        var reviews = mock(EnterpriseReviewMapper.class);
        var service = new EnterpriseOnboardingService(enterprises, reviews, null, null, null, ownership);
        var request = new ProfileRequest("Company",null,null,null,null,null,123L,null,null,null);
        var user = new LoginUser(99,"u","Owner",2,"");
        doThrow(new BizException(40301,"foreign attachment")).when(ownership).requireOwner(99,123L);
        assertThrows(BizException.class, () -> service.saveProfile(user,request));
        assertThrows(BizException.class, () -> service.submitReview(user,request));
        verify(ownership,times(2)).requireOwner(99,123L);
        verifyNoInteractions(enterprises,reviews);
    }
}
