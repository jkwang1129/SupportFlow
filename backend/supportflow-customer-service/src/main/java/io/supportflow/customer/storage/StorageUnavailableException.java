package io.supportflow.customer.storage;

import io.supportflow.common.exception.BizException;

public final class StorageUnavailableException extends BizException {
    public StorageUnavailableException() {
        super(50308, "附件存储暂不可用");
    }
}
