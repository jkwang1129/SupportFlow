package io.supportflow.customer.service.bot;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.supportflow.common.context.TenantContext;
import io.supportflow.customer.entity.KbDocument;
import io.supportflow.customer.internal.KbAiClient;
import io.supportflow.customer.internal.TenantListClient;
import io.supportflow.customer.mapper.KbDocumentMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KbVectorHealerTenantTest {
    @Test
    void repairsOnlyExplicitCandidatesWithinEachTenantsScope() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), KbDocument.class);
        var ai = mock(KbAiClient.class);
        var documents = mock(KbDocumentMapper.class);
        var kb = mock(KbService.class);
        var tenants = mock(TenantListClient.class);
        when(tenants.allTenantCodes()).thenReturn(List.of("TENANT_A", "TENANT_B"));
        when(ai.healthDetail(anyString())).thenAnswer(call -> {
            assertEquals(call.getArgument(0, String.class), TenantContext.get());
            return Map.of("embeddingKeyConfigured", true, "fallbackDocIds", List.of("7"));
        });
        when(documents.selectList(any())).thenAnswer(call -> List.of(
                KbDocument.builder().id(7L).tenantCode(TenantContext.get()).build(),
                KbDocument.builder().id(8L).tenantCode("FOREIGN").build()));
        when(kb.reindex(any(), eq(7L))).thenAnswer(call -> {
            var user = call.getArgument(0, io.supportflow.common.auth.LoginUser.class);
            assertEquals(user.tenantCode(), TenantContext.get());
            return null;
        });
        new KbVectorHealer(ai, documents, kb, tenants, true).heal();
        verify(kb, times(2)).reindex(any(), eq(7L));
        verify(kb, never()).reindex(any(), eq(8L));
        assertNull(TenantContext.get());
    }

    @Test
    void missingRepairCandidatesNeverFallsBackToReindexingWholeDatabase() {
        var ai = mock(KbAiClient.class);
        var documents = mock(KbDocumentMapper.class);
        var kb = mock(KbService.class);
        var tenants = mock(TenantListClient.class);
        when(tenants.allTenantCodes()).thenReturn(List.of("TENANT_A"));
        when(ai.healthDetail("TENANT_A")).thenReturn(Map.of("embeddingKeyConfigured", true));
        new KbVectorHealer(ai, documents, kb, tenants, true).heal();
        verifyNoInteractions(documents, kb);
    }
}
