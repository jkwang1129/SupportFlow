package io.supportflow.customer.service.bot;

import io.supportflow.common.auth.LoginUser;
import io.supportflow.common.context.TenantContext;
import io.supportflow.customer.entity.KbDocument;
import io.supportflow.customer.internal.KbAiClient;
import io.supportflow.customer.internal.TenantListClient;
import io.supportflow.customer.mapper.KbDocumentMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 知识库向量自愈：兜底向量 → 真实向量，不用人工一份份点「重新索引」。
 *
 * <p>要解决的问题是一类"看着好了其实没好"的状态：向量化失败（密钥没配 / 401 / 网络不通）时
 * 索引会**静默降级**成没有语义的 local-hash 向量。之后把密钥配好、服务重启，
 * 一切都显得正常，但库里的老向量还是兜底向量——检索只能按字面匹配，
 * 于是"知识库明明有数据，机器人却答不上来"，而且怎么重启都不会好。</p>
 *
 * <p>为什么放在 Java 侧：重新索引要拿到**原始文件字节**（存在 RustFS，由 customer-service 管），
 * AI 服务手里只有切片，够不到原文件。所以这件事只能由持有文件的这一侧发起。</p>
 *
 * <p>触发条件很保守，两个都满足才动手：</p>
 * <ol>
 *   <li>AI 侧体检发现库里确实有 local-hash 切片；</li>
 *   <li>现在向量密钥已经配好（否则重索引一遍还是兜底向量，白折腾）。</li>
 * </ol>
 *
 * <p>关掉：{@code --supportflow.kb.auto-reindex=false}</p>
 */
@Component
public class KbVectorHealer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KbVectorHealer.class);

    /** 上限：一次最多自愈这么多份文档，防止一次启动把向量接口打爆 */
    private static final int MAX_DOCS_PER_RUN = 200;

    private final KbAiClient aiClient;
    private final KbDocumentMapper documentMapper;
    private final KbService kbService;
    private final TenantListClient tenantListClient;
    private final boolean enabled;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public KbVectorHealer(
            KbAiClient aiClient,
            KbDocumentMapper documentMapper,
            KbService kbService,
            TenantListClient tenantListClient,
            @Value("${supportflow.kb.auto-reindex:true}") boolean enabled
    ) {
        this.aiClient = aiClient;
        this.documentMapper = documentMapper;
        this.kbService = kbService;
        this.tenantListClient = tenantListClient;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("知识库向量自愈已关闭（supportflow.kb.auto-reindex=false）");
            return;
        }
        // 单独线程跑：重索引要调向量接口，不能让启动流程等它
        Thread worker = new Thread(this::healQuietly, "kb-vector-healer");
        worker.setDaemon(true);
        worker.start();
    }

    private void healQuietly() {
        try {
            heal();
        } catch (Exception e) {
            // 自愈失败只是"没帮上忙"，不能影响服务本身
            log.warn("知识库向量自愈未完成（不影响启动）：{}", e.getMessage());
        }
    }

    void heal() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            int attempted = 0;
            for (String tenant : tenantListClient.allTenantCodes().stream().distinct().toList()) {
                if (!tenant.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,15}") || "PLATFORM".equalsIgnoreCase(tenant)) {
                    continue;
                }
                try {
                    int budget = MAX_DOCS_PER_RUN - attempted;
                    attempted += TenantContext.bind(tenant, () -> healTenant(tenant, budget));
                    if (attempted >= MAX_DOCS_PER_RUN) {
                        return;
                    }
                } catch (RuntimeException e) {
                    log.warn("知识库向量自愈跳过租户 {}：{}", tenant, e.getMessage());
                }
            }
        } finally {
            running.set(false);
        }
    }

    private int healTenant(String tenant, int budget) {
        Map<String, Object> health = aiClient.healthDetail(tenant);
        if (!Boolean.TRUE.equals(health.get("embeddingKeyConfigured"))) {
            return 0;
        }
        List<Long> fallbackDocs = fallbackDocIds(health);
        if (fallbackDocs.isEmpty()) {
            return 0;
        }
        int attempted = 0;
        for (KbDocument doc : documentsOf(tenant, fallbackDocs)) {
            if (tenant.equals(doc.getTenantCode()) && attempted < budget) {
                attempted++;
                reindexOne(doc);
            }
        }
        return attempted;
    }

    /**
     * 体检结果里"哪些文档用了兜底向量"。
     *
     * <p>只重建这几份（而不是整库重跑）：真按整库来，几千份文档的库启动一次就要重算几千次向量，
     * 白花钱还慢。拿不到清单时跳过。</p>
     */
    private List<Long> fallbackDocIds(Map<String, Object> health) {
        Object ids = health.get("fallbackDocIds");
        if (!(ids instanceof List<?> list)) {
            return List.of();
        }
        List<Long> result = new ArrayList<>(list.size());
        for (Object id : list) {
            try {
                result.add(Long.parseLong(String.valueOf(id)));
            } catch (NumberFormatException ignored) {
                // 单条解析不了就跳过，不影响其余文档
            }
        }
        return result;
    }

    private List<KbDocument> documentsOf(String tenantCode, List<Long> onlyIds) {
        var query = Wrappers.<KbDocument>lambdaQuery()
                .eq(KbDocument::getTenantCode, tenantCode)
                .eq(KbDocument::getDeleted, false)
                .in(KbDocument::getId, onlyIds)
                .orderByAsc(KbDocument::getId);
        return documentMapper.selectList(query);
    }

    private boolean reindexOne(KbDocument doc) {
        try {
            kbService.reindex(systemUser(doc.getTenantCode()), doc.getId());
            log.info("已重新索引 tenant={} docId={} title={}", doc.getTenantCode(), doc.getId(), doc.getTitle());
            return true;
        } catch (Exception e) {
            log.warn("重新索引失败 tenant={} docId={} title={} error={}",
                    doc.getTenantCode(), doc.getId(), doc.getTitle(), e.getMessage());
            return false;
        }
    }

    /** 自愈是系统行为：构造一个"系统操作人"，走和坐席点按钮时一样的入口 */
    private LoginUser systemUser(String tenantCode) {
        return new LoginUser(0L, "SYSTEM", "向量自愈", 2, tenantCode);
    }
}
