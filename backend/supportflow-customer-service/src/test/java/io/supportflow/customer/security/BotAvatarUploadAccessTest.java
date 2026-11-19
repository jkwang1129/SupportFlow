package io.supportflow.customer.security;

import io.supportflow.common.auth.LoginUser;
import io.supportflow.common.exception.BizException;
import io.supportflow.customer.mapper.FileMetaMapper;
import io.supportflow.customer.service.bot.BotAvatarService;
import io.supportflow.customer.storage.ObjectStorage;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class BotAvatarUploadAccessTest {
    @Test
    void publicReadingDoesNotGrantAnonymousOrPlatformUploadPrivileges() {
        var storage = mock(ObjectStorage.class);
        var files = mock(FileMetaMapper.class);
        var jdbc = mock(JdbcTemplate.class);
        var avatars = new BotAvatarService(storage, files, null, jdbc);
        assertThrows(BizException.class, () -> avatars.upload(null, null));
        assertThrows(BizException.class, () -> avatars.upload(
                new LoginUser(1L, "platform", "Admin", 1, "PLATFORM"), null));
        verifyNoInteractions(storage, files, jdbc);
    }
}
