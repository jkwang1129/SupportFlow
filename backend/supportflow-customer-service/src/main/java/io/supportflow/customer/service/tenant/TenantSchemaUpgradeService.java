package io.supportflow.customer.service.tenant;

import io.supportflow.common.exception.BizException;
import io.supportflow.common.id.SnowflakeIdGenerator;
import io.supportflow.customer.dto.tenant.Step;
import io.supportflow.customer.dto.tenant.UpgradeResult;
import io.supportflow.customer.entity.SchemaUpgradeLog;
import io.supportflow.customer.mapper.SchemaUpgradeLogMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 租户库升级执行器（数据面）。
 *
 * <p>独立库 / 独立 Schema 的租户需要跟共享库一样"升级到最新结构"。这个执行器把
 * TenantSchemaCatalog 里的增量脚本按版本顺序应用到目标 schema 上，并把每一步写进
 * schema_upgrade_log；因为脚本本身幂等（项目里所有增量脚本都这么写），
 * 所以失败可以直接重试、重复执行没有副作用。</p>
 *
 * <p>三个实现约定：</p>
 * <ol>
 *   <li>整份脚本一次执行：脚本里有 DO $$ … $$ 这种块，按分号切会把块切碎；
 *       Postgres 的 JDBC 支持一次发多条语句（简单查询协议），所以整份丢给 Statement.execute；</li>
 *   <li>目标 schema 用 SET search_path：脚本里的表名都不带 schema 前缀，
 *       search_path 指到租户 schema 就落在租户自己的库里；
 *       schema 名经过严格白名单校验，拼不进注入；</li>
 *   <li>失败即停、已成功的保留：不做整批回滚，因为脚本幂等，重试会从下一版继续。</li>
 * </ol>
 */
@Service
public class TenantSchemaUpgradeService {

    private static final Logger log = LoggerFactory.getLogger(TenantSchemaUpgradeService.class);

    /** schema 名白名单：小写字母开头，后面是字母数字下划线，最长 63（PG 标识符上限） */
    private static final Pattern SCHEMA_NAME = Pattern.compile("^[a-z_][a-z0-9_]{0,62}$");

    /** 共享库的 schema */
    public static final String SHARED_SCHEMA = "public";

    private final DataSource dataSource;
    private final SchemaUpgradeLogMapper logMapper;
    private final SnowflakeIdGenerator idGenerator;

    public TenantSchemaUpgradeService(DataSource dataSource,
                                      SchemaUpgradeLogMapper logMapper,
                                      SnowflakeIdGenerator idGenerator) {
        this.dataSource = dataSource;
        this.logMapper = logMapper;
        this.idGenerator = idGenerator;
    }

    /** 这个库当前版本：取日志里最近一次成功的版本，没有记录就是 0（只有全量基线） */
    public int currentVersion(String tenantCode, String targetSchema) {
        String schema = normalizeSchema(targetSchema);
        SchemaUpgradeLog latest = logMapper.selectOne(Wrappers.<SchemaUpgradeLog>lambdaQuery()
                .eq(SchemaUpgradeLog::getTenantCode, tenantCode)
                .eq(SchemaUpgradeLog::getTargetSchema, schema)
                .eq(SchemaUpgradeLog::getStatus, 1)
                .orderByDesc(SchemaUpgradeLog::getVersion)
                .last("LIMIT 1"));
        return latest == null || latest.getVersion() == null ? 0 : latest.getVersion();
    }

    /** 升级到指定版本（不传就是最新）；已经是最新则直接返回 */
    public UpgradeResult upgrade(String tenantCode, String targetSchema, Integer toVersion, String operatorName) {
        if (tenantCode == null || tenantCode.isBlank()) {
            throw new BizException(40001, "缺少租户编码");
        }
        String schema = normalizeSchema(targetSchema);
        int target = toVersion == null ? TenantSchemaCatalog.LATEST_VERSION : toVersion;
        if (target != 0 && TenantSchemaCatalog.of(target) == null) {
            throw new BizException(40001, "未知的库版本：" + target
                    + "（最新为 " + TenantSchemaCatalog.LATEST_VERSION + "）");
        }
        int from = currentVersion(tenantCode, schema);
        List<String> applied = new ArrayList<>();
        if (target <= from) {
            return new UpgradeResult(from, from, applied, "已是最新版，无需执行");
        }

        int version = from;
        for (Step step : TenantSchemaCatalog.STEPS) {
            if (step.version() <= from || step.version() > target) {
                continue;
            }
            long start = System.currentTimeMillis();
            String message = null;
            int status = 1;
            try {
                runScript(step.script(), schema);
                version = step.version();
            } catch (Exception e) {
                status = 2;
                message = shorten(e.getMessage());
                log.error("租户库升级失败 tenant={} schema={} version={} script={}",
                        tenantCode, schema, step.version(), step.script(), e);
            }
            int duration = (int) (System.currentTimeMillis() - start);
            logMapper.insert(SchemaUpgradeLog.builder()
                    .id(idGenerator.nextId())
                    .tenantCode(tenantCode)
                    .targetSchema(schema)
                    .version(step.version())
                    .script(step.script())
                    .title(step.title())
                    .status(status)
                    .durationMs(duration)
                    .message(message)
                    .tenantDbVersion(status == 1 ? step.version() : version)
                    .appliedAt(LocalDateTime.now())
                    .createTime(LocalDateTime.now())
                    .updateTime(LocalDateTime.now())
                    .build());
            if (status == 2) {
                throw new BizException(50002, "升级失败：" + step.script() + "（" + message
                        + "）；已成功的版本保留，修好后可重试");
            }
            applied.add(step.script());
            log.info("租户库升级 tenant={} schema={} version={} script={} 用时 {}ms",
                    tenantCode, schema, step.version(), step.script(), duration);
        }
        return new UpgradeResult(from, version, applied, null);
    }

    /** 待升级清单（平台侧"这次会执行哪些脚本"预览用） */
    public List<Step> pending(String tenantCode, String targetSchema) {
        int from = currentVersion(tenantCode, targetSchema);
        return TenantSchemaCatalog.STEPS.stream()
                .filter(step -> step.version() > from)
                .toList();
    }

    /** 执行单个脚本：切 schema → 整份执行 → 切回（同一个连接） */
    private void runScript(String script, String schema) {
        ClassPathResource resource = new ClassPathResource("schema/" + script);
        if (!resource.exists()) {
            throw new BizException(50002, "运行期缺少脚本副本：" + script
                    + "（resources/schema 下应有它，跑 scripts/check-schema-copies.sh 核对）");
        }
        String sql;
        try (InputStream in = resource.getInputStream()) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BizException(50002, "读取脚本失败：" + script);
        }
        boolean shared = SHARED_SCHEMA.equals(schema);
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            if (!shared) {
                statement.execute("SET search_path TO \"" + schema + "\", public");
            }
            try {
                // 整份执行：脚本里有 DO $$ … $$ 块，按分号切会把块切碎
                statement.execute(sql);
            } finally {
                if (!shared) {
                    statement.execute("SET search_path TO public");
                }
            }
        } catch (Exception e) {
            throw new BizException(50002, "执行 " + script + " 失败：" + shorten(e.getMessage()));
        }
    }

    private static String normalizeSchema(String targetSchema) {
        String schema = targetSchema == null || targetSchema.isBlank()
                ? SHARED_SCHEMA : targetSchema.trim();
        if (!SCHEMA_NAME.matcher(schema).matches()) {
            throw new BizException(40001, "schema 名不合法（只允许小写字母、数字、下划线，字母或下划线开头）");
        }
        return schema;
    }

    private static String shorten(String message) {
        if (message == null) {
            return null;
        }
        String flat = message.replaceAll("[\\r\\n]+", " ").trim();
        return flat.length() > 400 ? flat.substring(0, 400) + "…" : flat;
    }
}
