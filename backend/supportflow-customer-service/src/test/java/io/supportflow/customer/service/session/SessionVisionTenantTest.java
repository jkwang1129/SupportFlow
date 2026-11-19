package io.supportflow.customer.service.session;

import io.supportflow.common.context.TenantContext;
import io.supportflow.common.exception.BizException;
import io.supportflow.customer.entity.FileMeta;
import io.supportflow.customer.mapper.FileMetaMapper;
import io.supportflow.customer.storage.ObjectStorage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SessionVisionTenantTest {
    private final FileMetaMapper files = mock(FileMetaMapper.class);
    private final ObjectStorage storage = mock(ObjectStorage.class);
    private final io.supportflow.customer.mapper.SessionMapper sessions = mock(io.supportflow.customer.mapper.SessionMapper.class);
    private final SessionAttachmentService service = new SessionAttachmentService(storage, files, null, null, null, 8, sessions);

    @org.junit.jupiter.api.BeforeEach
    void sessions() {
        when(sessions.selectOne(any())).thenAnswer(call -> io.supportflow.customer.entity.Session.builder()
                .id(1L).tenantCode(TenantContext.get()).sessionNo("S1").build());
    }

    @org.junit.jupiter.api.AfterEach
    void shutdown() {
        service.shutdownVisionPool();
    }

    @Test
    void imageWorkersUseTheirTaskTenantAndRestoreCallingContext() throws Exception {
        var scopes = new ConcurrentLinkedQueue<String>();
        when(files.selectOne(any())).thenAnswer(call -> {
            String scope = TenantContext.get();
            scopes.add(scope);
            return FileMeta.builder().tenantCode(scope).bizType(5).bizId(1L).objectKey(scope).build();
        });
        when(storage.download(any())).thenAnswer(call -> new ByteArrayInputStream(new byte[]{1}));
        TenantContext.run("CALLER", () -> {
            assertEquals(3, service.toVisionImages("TENANT_A", "S1", List.of(1L, 2L, 3L)).size());
            assertEquals(List.of("TENANT_A", "TENANT_A", "TENANT_A"), List.copyOf(scopes));
            scopes.clear();
            assertEquals(2, service.toVisionImages("TENANT_B", "S1", List.of(4L, 5L)).size());
            assertEquals(List.of("TENANT_B", "TENANT_B"), List.copyOf(scopes));
            assertEquals("CALLER", TenantContext.get());
        });
    }

    @Test
    void refusesForeignFilesBeforeReadingObjectStorage() {
        when(files.selectOne(any())).thenReturn(FileMeta.builder().tenantCode("TENANT_B")
                .bizType(5).objectKey("different-tenant").build());
        assertThrows(BizException.class, () -> service.toVisionImages("TENANT_A", "S1", List.of(1L)));
        assertThrows(IllegalArgumentException.class, () -> service.toVisionImages(null, "S1", List.of(1L)));
        verifyNoInteractions(storage);
    }

    @Test
    void refusesOtherSessionAndUnrelatedBusinessFilesIncludingLegacyPaths() throws Exception {
        TenantContext.run("TENANT_A", () -> {
            when(files.selectOne(any())).thenReturn(FileMeta.builder().tenantCode("TENANT_A")
                    .bizType(5).bizId(2L).objectKey("chat/TENANT_A/S2/image").build());
            assertThrows(BizException.class, () -> service.load("TENANT_A", "S1", 1));
            when(files.selectOne(any())).thenReturn(FileMeta.builder().tenantCode("TENANT_A")
                    .bizType(5).objectKey("chat/TENANT_A/S10/image").build());
            assertThrows(BizException.class, () -> service.load("TENANT_A", "S1", 1));
            when(files.selectOne(any())).thenReturn(FileMeta.builder().tenantCode("TENANT_A")
                    .bizType(2).bizId(1L).objectKey("enterprise/license").build());
            assertThrows(BizException.class, () -> service.load("TENANT_A", "S1", 1));
        });
        verifyNoInteractions(storage);
        when(storage.download(any())).thenReturn(new ByteArrayInputStream(new byte[]{1}));
        when(files.selectOne(any())).thenReturn(FileMeta.builder().tenantCode("TENANT_A")
                .bizType(5).objectKey("chat/TENANT_A/S1/image").build());
        TenantContext.run("TENANT_A", () -> assertEquals(1, service.load("TENANT_A", "S1", 1).payload().length));
    }
}
