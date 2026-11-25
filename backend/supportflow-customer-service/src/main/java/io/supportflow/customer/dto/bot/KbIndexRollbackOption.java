package io.supportflow.customer.dto.bot;

/** Rollback candidate metadata without archived document body or vectors. */
public record KbIndexRollbackOption(String versionId, int chunkCount) {}
