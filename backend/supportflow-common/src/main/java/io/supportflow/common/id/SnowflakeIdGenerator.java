package io.supportflow.common.id;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 雪花 ID 生成器（应用层分配主键，数据库不自增）。
 *
 * <p>结构：1 位符号位 + 41 位毫秒时间戳 + 5 位机房/机器位 + 5 位工作位 + 12 位序列号。
 * 本骨架将机房位与工作位合并为 10 位 workerId（0 ~ 1023），
 * 单实例支持 4096/ms，多实例通过环境变量 {@code SUPPORTFLOW_WORKER_ID} 区分。
 */
@Component
public class SnowflakeIdGenerator {

    /** 起始时间戳：2025-01-01 00:00:00（可自定义，保证小于当前时间即可）。 */
    private static final long EPOCH = 1735689600000L;

    private static final long WORKER_ID_BITS = 10L;
    private static final long SEQUENCE_BITS = 12L;

    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);
    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);

    private final long workerId;
    private long lastTimestamp = -1L;
    private long sequence = 0L;

    public SnowflakeIdGenerator(@Value("${supportflow.id.worker-id:1}") long workerId) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException("workerId 必须在 0 ~ " + MAX_WORKER_ID + " 之间");
        }
        this.workerId = workerId;
    }

    /**
     * 生成下一个全局唯一 ID（线程安全）。
     *
     * @return 雪花 ID
     */
    public synchronized long nextId() {
        long timestamp = System.currentTimeMillis();
        if (timestamp < lastTimestamp) {
            // 时钟回拨：简单等待追上，避免产生重复 ID
            long offset = lastTimestamp - timestamp;
            if (offset > 5) {
                throw new IllegalStateException("时钟回拨过大，拒绝生成 ID");
            }
            try {
                Thread.sleep(offset + 1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("时钟等待被中断", e);
            }
            timestamp = System.currentTimeMillis();
        }
        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                timestamp = waitNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = timestamp;
        return ((timestamp - EPOCH) << (WORKER_ID_BITS + SEQUENCE_BITS))
                | (workerId << SEQUENCE_BITS)
                | sequence;
    }

    private long waitNextMillis(long lastTimestamp) {
        long timestamp = System.currentTimeMillis();
        while (timestamp <= lastTimestamp) {
            timestamp = System.currentTimeMillis();
        }
        return timestamp;
    }
}
