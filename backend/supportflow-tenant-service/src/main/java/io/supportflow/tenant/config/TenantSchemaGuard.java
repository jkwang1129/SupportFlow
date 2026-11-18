package io.supportflow.tenant.config;

import io.supportflow.tenant.mapper.SchemaMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * tenant_db 结构自检（<b>只读</b>，启动时不会改库）。
 *
 * <p>结构变更统一交给**一次性迁移**：`bash scripts/migrate-tenant-db.sh`（脚本幂等，跑一次即可，
 * 24 个 customer 脚本同理见 `scripts/migrate-customer-db.sh`）。应用启动只做两件事：</p>
 * <ol>
 *   <li><b>核对运行期脚本副本</b>：根目录 {@code schema/} 是权威版本，真正执行的是
 *       {@code resources/schema/} 下的副本——少了就是"打包漏文件"，这跟库状态无关；</li>
 *   <li><b>比对表 / 列</b>：代码依赖的表和列在不在库里，缺了就在启动日志里说清楚
 *       "缺什么、来自哪个脚本、该执行哪条命令"。</li>
 * </ol>
 *
 * <p><b>为什么不在这里自动补</b>：应用账号不该要 DDL 权限；多实例并发启动会抢锁；
 * 而且"启动时自己补上"会掩盖"这个环境没人跑迁移"的流程问题。customer-service 那一侧的
 * {@code SchemaGuard} 也是因此移除的（见 {@code legacy/removed-schema-guard/README.md}）。</p>
 *
 * <p><b>为什么 tenant-service 还留这个只读自检</b>：租户库是<b>按租户动态创建</b>的，
 * 一次全局迁移覆盖不到"以后新建/很久没动过的那些租户库"；启动时把不一致喊出来，
 * 比等网关每 5 秒上报用量时炸 {@code relation "tenant_usage_daily" does not exist} 划算得多。</p>
 */
@Component
public class TenantSchemaGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TenantSchemaGuard.class);

    /** 需要核对的增量脚本（与 scripts/migrate-tenant-db.sh 保持一致） */
    private static final List<String> SCRIPT_ORDER = List.of(
            "tenant_db_isolation.sql",
            "tenant_db_security_handling.sql"
    );

    /** 代码依赖的表 → 来源脚本 */
    private static final Map<String, String> REQUIRED_TABLES = new LinkedHashMap<>();
    /** 代码依赖的列 → 来源脚本 */
    private static final Map<String, String> REQUIRED_COLUMNS = new LinkedHashMap<>();

    static {
        REQUIRED_TABLES.put("tenant_quota", "tenant_db_isolation.sql");
        REQUIRED_TABLES.put("tenant_schema_upgrade", "tenant_db_isolation.sql");
        REQUIRED_TABLES.put("tenant_security_event", "tenant_db_isolation.sql");
        REQUIRED_TABLES.put("tenant_usage_daily", "tenant_db_isolation.sql");

        REQUIRED_COLUMNS.put("tenant.isolation_mode", "tenant_db_isolation.sql");
        REQUIRED_COLUMNS.put("tenant.schema_name", "tenant_db_isolation.sql");
        REQUIRED_COLUMNS.put("tenant.db_version", "tenant_db_isolation.sql");
    }

    private final SchemaMapper schemaMapper;

    public TenantSchemaGuard(SchemaMapper schemaMapper) {
        this.schemaMapper = schemaMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            checkScriptCopies();
            Map<String, List<String>> missing = missingObjects();
            if (missing.isEmpty()) {
                log.info("tenant_db 结构自检通过（{} 张表 / {} 个列都在）",
                        REQUIRED_TABLES.size(), REQUIRED_COLUMNS.size());
                return;
            }
            log.error("""
                            
                            tenant_db 结构不完整（启动自检只读，不会自动改库）：
                              {}
                              涉及脚本：{}
                              请执行：bash scripts/migrate-tenant-db.sh""",
                    describe(missing), String.join("、", missing.keySet()));
        } catch (Exception e) {
            // 自检自身出错不拦启动：服务照起，问题写进日志
            log.warn("tenant_db 结构自检未完成（不影响启动，但相关功能可能报错）：{}", e.getMessage());
        }
    }

    private Map<String, List<String>> missingObjects() {
        Set<String> tables = new HashSet<>(schemaMapper.selectTables());
        Set<String> columns = new HashSet<>(schemaMapper.selectColumns());
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : REQUIRED_TABLES.entrySet()) {
            if (!tables.contains(entry.getKey())) {
                result.computeIfAbsent(entry.getValue(), key -> new ArrayList<>())
                        .add("表 " + entry.getKey());
            }
        }
        for (Map.Entry<String, String> entry : REQUIRED_COLUMNS.entrySet()) {
            if (!columns.contains(entry.getKey())) {
                result.computeIfAbsent(entry.getValue(), key -> new ArrayList<>())
                        .add("列 " + entry.getKey());
            }
        }
        return result;
    }

    private static String describe(Map<String, List<String>> missing) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : missing.entrySet()) {
            parts.add(String.join("、", entry.getValue()) + " ← " + entry.getKey());
        }
        return String.join("；", parts);
    }

    /**
     * 核对"增量脚本的运行期副本"是否齐全。
     *
     * <p>这一步跟库状态无关，是防"打包漏文件"：改脚本只改根目录、忘了同步
     * {@code resources/schema/}，另一个环境部署时就会因为找不到脚本而补不上结构。</p>
     */
    private void checkScriptCopies() {
        List<String> missing = new ArrayList<>();
        for (String script : SCRIPT_ORDER) {
            if (!new ClassPathResource("schema/" + script).exists()) {
                missing.add(script);
            }
        }
        if (missing.isEmpty()) {
            log.info("tenant_db 增量脚本副本齐全（{} 个）", SCRIPT_ORDER.size());
            return;
        }
        log.error("\n运行期缺少增量脚本副本：{}\n"
                        + "  根目录 schema/ 是权威版本，请复制到 "
                        + "supportflow-tenant-service/src/main/resources/schema/ 后重新打包启动；\n"
                        + "  或手动执行：bash scripts/migrate-tenant-db.sh",
                String.join("、", missing));
    }
}
